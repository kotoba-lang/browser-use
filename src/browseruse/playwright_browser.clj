(ns browseruse.playwright-browser
  "Owned, daemon-free Playwright JVM host.

  Every session owns its Playwright, Browser and BrowserContext.  `:close` is
  idempotent and closes all three, making it suitable for `try`/`finally`."
  (:require [browseruse.browser :as b]
            [clojure.java.io :as io])
  (:import [com.microsoft.playwright Browser Browser$NewContextOptions
            BrowserContext BrowserContext$StorageStateOptions BrowserType$LaunchOptions
            Page Page$ScreenshotOptions Page$WaitForFunctionOptions
            Playwright]
           [com.microsoft.playwright.options Cookie LoadState ScreenshotType]
           [java.nio.file Files Path Paths]
           [java.util UUID]
           [java.util.function Consumer]))

(defn- java->clj [x]
  (cond
    (instance? java.util.Map x)
    (into {} (map (fn [[k v]] [(keyword (str k)) (java->clj v)]) x))
    (instance? java.util.List x) (mapv java->clj x)
    :else x))

(def ^:private els-js
  "[...document.querySelectorAll('a,button,input,textarea,select')].filter(el => el.offsetParent !== null)")

(defn- interactive-elements [^Page page]
  (java->clj
   (.evaluate page (str "() => " els-js ".map((el,index)=>({index,tag:el.tagName.toLowerCase(),"
                        "text:(el.innerText||el.value||el.placeholder||'').trim(),attrs:{name:el.name||'',"
                        "type:el.type||'',placeholder:el.placeholder||'',value:el.value||'',href:el.href||''}}))"))))

(defn- page-state [^Page page]
  {:url (.url page) :title (.title page) :elements (interactive-elements page)})

(defn- locator [^Page page index]
  (.nth (.locator page "a:visible,button:visible,input:visible,textarea:visible,select:visible") index))

(defn- wait-load! [^Page page]
  (try (.waitForLoadState page LoadState/DOMCONTENTLOADED)
       (catch com.microsoft.playwright.PlaywrightException _ nil)))

(defn- make-browser [page* ops*]
  (reify
    b/IBrowser
    (-navigate! [_ url] (.navigate ^Page @page* url) (wait-load! @page*) (page-state @page*))
    (-back! [_] (.goBack ^Page @page*) (wait-load! @page*) (page-state @page*))
    (-scroll! [_ direction]
      (.evaluate ^Page @page* "d => window.scrollBy(0,d==='up'?-innerHeight:innerHeight)" (name direction))
      (page-state @page*))
    (-state [_] (page-state @page*))
    (-click! [_ index] (.click (locator @page* index)) (page-state @page*))
    (-input-text! [_ index text] (.fill (locator @page* index) text) (page-state @page*))
    b/ITabBrowser
    (-tabs [_] ((:tabs @ops*)))
    (-new-tab! [_ url] ((:new-tab @ops*) url))
    (-switch-tab! [_ id] ((:switch-tab @ops*) id))
    (-close-tab! [_ id] ((:close-tab @ops*) id))
    b/IInteractionBrowser
    (-wait! [_ spec] ((:wait @ops*) spec))
    (-evaluate! [_ expression args] ((:evaluate @ops*) expression args))
    (-screenshot! [_ opts] ((:screenshot @ops*) opts))
    (-upload-files! [_ index files] ((:upload-files @ops*) index files))
    (-downloads [_] ((:downloads @ops*)))
    (-select-option! [_ index values] ((:select-option @ops*) index values))
    (-set-checked! [_ index checked?] ((:set-checked @ops*) index checked?))
    (-press-keys! [_ keys] ((:press-keys @ops*) keys))
    (-extract [_ query] ((:extract @ops*) query))
    b/IStorageBrowser
    (-cookies [_ urls] ((:cookies @ops*) urls))
    (-set-cookies! [_ cookies] ((:set-cookies @ops*) cookies))
    (-clear-cookies! [_] ((:clear-cookies @ops*)))
    (-storage-state [_] ((:storage-state @ops*)))
    (-set-storage! [_ storage] ((:set-storage @ops*) storage))))

(defn- path [p] (Paths/get (str p) (make-array String 0)))

(defn- cookie->map [^Cookie c]
  (cond-> {:name (.-name c) :value (.-value c) :domain (.-domain c) :path (.-path c)
           :expires (.-expires c) :http-only (.-httpOnly c) :secure (.-secure c)
           :same-site (some-> (.-sameSite c) str)}
    (.-url c) (assoc :url (.-url c))))

(defn- cookie-from-map [{:keys [name value url domain path expires http-only secure same-site]}]
  (let [c (Cookie. name value)]
    (when url (set! (.-url c) url))
    (when domain (set! (.-domain c) domain))
    (when path (set! (.-path c) path))
    (when expires (set! (.-expires c) (double expires)))
    (when (some? http-only) (set! (.-httpOnly c) (boolean http-only)))
    (when (some? secure) (set! (.-secure c) (boolean secure)))
    ;; sameSite is intentionally omitted when absent; Playwright validates it.
    c))

(defn playwright-session
  "Launch an isolated Chromium session.

  Options: `:headless?`, `:timeout`, `:viewport {:width :height}`,
  `:user-agent`, `:locale`, `:timezone-id`, `:storage-state-path`, and
  `:accept-downloads?`. The returned capability functions are synchronous.
  Call `:close` from `finally`; it is idempotent."
  ([start-url] (playwright-session start-url {}))
  ([start-url {:keys [headless? timeout viewport user-agent locale timezone-id
                      storage-state-path accept-downloads? executable-path channel]
               :or {headless? true timeout 30000 accept-downloads? true}}]
   (let [pw (Playwright/create)
         launch-opts (doto (BrowserType$LaunchOptions.) (.setHeadless headless?))
         _ (when executable-path (.setExecutablePath launch-opts (path executable-path)))
         _ (when channel (.setChannel launch-opts channel))
         raw-browser (-> pw .chromium (.launch launch-opts))
         context-opts (doto (Browser$NewContextOptions.)
                        (.setAcceptDownloads accept-downloads?))
         _ (when viewport (.setViewportSize context-opts (int (:width viewport)) (int (:height viewport))))
         _ (when user-agent (.setUserAgent context-opts user-agent))
         _ (when locale (.setLocale context-opts locale))
         _ (when timezone-id (.setTimezoneId context-opts timezone-id))
         _ (when storage-state-path (.setStorageStatePath context-opts (path storage-state-path)))
         ctx (.newContext ^Browser raw-browser context-opts)
         page (.newPage ^BrowserContext ctx)
         page* (atom page)
         active-id* (atom nil)
         ops* (atom nil)
         tabs* (atom {})
         events* (atom {:console [] :page-errors [] :request-failures [] :downloads []})
         closed? (atom false)
         register! (fn [^Page p]
                     (let [id (str (UUID/randomUUID))]
                       (swap! tabs* assoc id p)
                       (.onConsoleMessage p (reify Consumer (accept [_ m]
                                                        (swap! events* update :console conj
                                                               {:type (.type m) :text (.text m)}))))
                       (.onPageError p (reify Consumer (accept [_ e]
                                                 (swap! events* update :page-errors conj
                                                        {:message (str e)}))))
                       (.onRequestFailed p (reify Consumer (accept [_ r]
                                                     (swap! events* update :request-failures conj
                                                            {:url (.url r) :method (.method r)
                                                             :failure (.failure r)}))))
                       (.onDownload p (reify Consumer (accept [_ d]
                                                (swap! events* update :downloads conj
                                                       {:url (.url d) :filename (.suggestedFilename d)
                                                        :download d}))))
                       id))
         initial-id (register! page)
         tab-id (fn [p] (some (fn [[id candidate]] (when (identical? p candidate) id)) @tabs*))
         session
         {:browser (make-browser page* ops*)
          :page page*
          :context ctx
          :tab-id active-id*
          :tabs (fn [] (mapv (fn [[id ^Page p]] {:id id :url (.url p) :title (.title p)
                                                  :active? (identical? p @page*)}) @tabs*))
          :new-tab (fn [url]
                     (let [p (.newPage ctx) id (register! p)]
                       (.setDefaultTimeout p timeout)
                       (when url (.navigate p url))
                       (reset! page* p)
                       (reset! active-id* id)
                       {:id id :state (page-state p)}))
          :switch-tab (fn [id]
                        (if-let [p (get @tabs* id)]
                          (do (reset! page* p) (reset! active-id* id) (page-state p))
                          (throw (ex-info "Unknown tab" {:id id}))))
          :close-tab (fn [id]
                       (when-not (contains? @tabs* id)
                         (throw (ex-info "Unknown tab" {:id id})))
                       (when (= 1 (count @tabs*))
                         (throw (ex-info "Cannot close the last tab" {:id id})))
                       (let [^Page p (get @tabs* id) active? (identical? @page* p)]
                         (.close p)
                         (swap! tabs* dissoc id)
                         (when active?
                           (let [[next-id next-page] (first @tabs*)]
                             (reset! page* next-page)
                             (reset! active-id* next-id))))
                       {:closed id})
          :evaluate (fn
                      ([expression] (java->clj (.evaluate ^Page @page* expression)))
                      ([expression args] (java->clj (.evaluate ^Page @page* expression args))))
          :wait (fn [{:keys [function selector load-state timeout-ms ms condition]}]
                  (let [{condition-function :function condition-selector :selector
                         condition-load :load-state} (when (map? condition) condition)
                        function (or function condition-function (when (string? condition) condition))
                        selector (or selector condition-selector)
                        load-state (or load-state condition-load)]
                  (cond
                    ms (Thread/sleep (long ms))
                    function (.waitForFunction ^Page @page* function nil
                                               (doto (Page$WaitForFunctionOptions.)
                                                 (.setTimeout (double (or timeout-ms timeout)))))
                    selector (.waitForSelector ^Page @page* selector)
                    load-state (.waitForLoadState ^Page @page* (LoadState/valueOf (.toUpperCase (name load-state))))
                    :else (throw (ex-info "Wait requires :ms, :condition, :function, :selector, or :load-state" {}))))
                  (page-state @page*))
          :screenshot (fn [{:keys [path full-page? type format]
                            :or {full-page? true}}]
                        (let [format (or format type :png)
                              opts (doto (Page$ScreenshotOptions.)
                                     (.setFullPage full-page?)
                                     (.setType (case format :jpeg ScreenshotType/JPEG :png ScreenshotType/PNG
                                                     (throw (ex-info "Unsupported screenshot format" {:format format})))))
                              _ (when path (.setPath opts (browseruse.playwright-browser/path path)))
                              bytes (.screenshot ^Page @page* opts)
                              ]
                          {:bytes bytes :path path :format format :size (alength bytes)}))
          :upload-files (fn [index paths]
                          (.setInputFiles (locator @page* index) (into-array Path (map path paths)))
                          (page-state @page*))
          :downloads (fn [] (mapv #(dissoc % :download) (:downloads @events*)))
          :save-download (fn [download-index destination]
                           (let [d (:download (nth (:downloads @events*) download-index))]
                             (.saveAs d (path destination)) destination))
          :select-option (fn [index values]
                           (let [values (if (sequential? values) values [values])]
                             (.selectOption (locator @page* index)
                                            (into-array String (map str values))))
                           (page-state @page*))
          :set-checked (fn [index checked?] (.setChecked (locator @page* index) checked?) (page-state @page*))
          :press-keys (fn [keys]
                        (doseq [key (if (sequential? keys) keys [keys])]
                          (.press (.keyboard ^Page @page*) (str key)))
                        (page-state @page*))
          :extract (fn [{:keys [selector attribute all?] :or {selector "body"}}]
                     (let [loc (.locator ^Page @page* selector)
                           value (fn [item] (if attribute (.getAttribute item (name attribute)) (.innerText item)))]
                       (if all? (mapv value (.all loc)) (value (.first loc)))))
          :cookies (fn
                     ([] (mapv cookie->map (.cookies ctx)))
                     ([urls] (mapv cookie->map
                                   (if (seq urls)
                                     (.cookies ctx (into-array String urls))
                                     (.cookies ctx)))))
          :set-cookies (fn [cookies] (.addCookies ctx (mapv cookie-from-map cookies)) {:count (count cookies)})
          :clear-cookies (fn [] (.clearCookies ctx) {:cleared true})
          :storage-state (fn
                           ([] (java->clj (.storageState ctx)))
                           ([destination] (.storageState ctx (doto (BrowserContext$StorageStateOptions.)
                                                             (.setPath (path destination)))) destination))
          :set-storage (fn [{:keys [cookies origins]}]
                         (when cookies (.addCookies ctx (mapv cookie-from-map cookies)))
                         (doseq [{:keys [origin local-storage]} origins]
                           (let [p (.newPage ctx)]
                             (try
                               (.navigate p origin)
                               (.evaluate p "entries => { localStorage.clear(); for (const e of entries) localStorage.setItem(e.name,e.value) }"
                                          local-storage)
                               (finally (.close p)))))
                         {:origins (count origins)})
          :events (fn [] (update @events* :downloads #(mapv (fn [x] (dissoc x :download)) %)))
          :close (fn []
                   (when (compare-and-set! closed? false true)
                     (try (.close ctx) (catch Throwable _))
                     (try (.close raw-browser) (catch Throwable _))
                     (try (.close pw) (catch Throwable _)))
                   {:closed true})}]
     (.setDefaultTimeout page timeout)
     (when start-url (.navigate page start-url))
     (reset! active-id* initial-id)
     (reset! ops* session)
     session)))

(defn playwright-browser
  ([start-url] (playwright-browser start-url {}))
  ([start-url opts] (:browser (playwright-session start-url opts))))
