(ns browseruse.playwright-browser
  "IBrowser implementation over Microsoft Playwright (JVM-only).

  Loaded only when com.microsoft.playwright/playwright is on the classpath
  (see :playwright alias in deps.edn). Do not require from .cljc namespaces.

  Targets the page DOM (indexed interactive elements) in Playwright's OWN
  Chromium context — no OS mouse/keyboard, no window focus or Space contention,
  so it is far more reliable than coordinate-driven desktop automation.

  Inference route for the model: Murakumo LiteLLM 127.0.0.1:4000 (ADR-2605215000)."
  (:require [browseruse.browser :as b])
  (:import [com.microsoft.playwright Playwright BrowserType$LaunchOptions Page$ScreenshotOptions]
           [java.nio.file Paths]))

(defn- java->clj
  "Recursively convert Java Map/List (returned by Playwright page.evaluate)
  into Clojure maps/vectors with keyword keys."
  [x]
  (cond
    (instance? java.util.Map x)
    (into {} (map (fn [[k v]] [(keyword k) (java->clj v)]) x))
    (instance? java.util.List x)
    (mapv java->clj x)
    :else x))

(defn- interactive-elements [^com.microsoft.playwright.Page page]
  (-> page
      (.evaluate
       (str "() => {"
            "  const TAGS = 'a,button,input,textarea,select';"
            "  return [...document.querySelectorAll(TAGS)]"
            "    .filter(el => el.offsetParent !== null)"
            "    .map((el, i) => ({"
            "      index: i,"
            "      tag:   el.tagName.toLowerCase(),"
            "      text:  (el.innerText || el.value || el.placeholder || '').trim(),"
            "      attrs: { name: el.name || '',"
            "               type: el.type || '',"
            "               placeholder: el.placeholder || '',"
            "               value: el.value || '',"
            "               href:  el.href  || '' }})); }"))
      java->clj))

(defn- page-state [^com.microsoft.playwright.Page page]
  {:url      (.url page)
   :title    (.title page)
   :elements (interactive-elements page)})

(defn- click-nth! [^com.microsoft.playwright.Page page index]
  (.evaluate page
             (str "() => { const els = [...document.querySelectorAll('a,button,input,textarea,select')]"
                  "  .filter(el => el.offsetParent !== null);"
                  "  if (els[" index "]) els[" index "].click(); }")))

(defn- type-into-nth! [^com.microsoft.playwright.Page page index text]
  (.evaluate page
             (str "() => { const els = [...document.querySelectorAll('a,button,input,textarea,select')]"
                  "  .filter(el => el.offsetParent !== null);"
                  "  if (els[" index "]) { els[" index "].focus();"
                  "    if ('value' in els[" index "]) els[" index "].value = ''; } }"))
  (.type (.keyboard page) text))

(def ^:private els-js
  "[...document.querySelectorAll('a,button,input,textarea,select')].filter(el => el.offsetParent !== null)")

(defn- select-nth!
  "Set the <select> at the filtered index to the option whose text or value
  matches `wanted` (substring, case-insensitive), dispatching a change event."
  [^com.microsoft.playwright.Page page index wanted]
  (.evaluate page
             (str "() => { const els = " els-js "; const el = els[" index "];"
                  "  if (el && el.tagName === 'SELECT') {"
                  "    const w = " (pr-str wanted) ".toLowerCase();"
                  "    const opt = [...el.options].find(o =>"
                  "      o.text.trim().toLowerCase().includes(w) || o.value.toLowerCase() === w);"
                  "    if (opt) { el.value = opt.value;"
                  "      el.dispatchEvent(new Event('change', {bubbles:true})); } } }")))

(defn- check-nth!
  "Ensure the checkbox at the filtered index is checked (clicks if not)."
  [^com.microsoft.playwright.Page page index]
  (.evaluate page
             (str "() => { const els = " els-js "; const el = els[" index "];"
                  "  if (el && el.type === 'checkbox' && !el.checked) el.click(); }")))

(defn- make-browser [^com.microsoft.playwright.Page page]
  (reify b/IBrowser
    (-navigate! [_ url] (.navigate page url) (.waitForLoadState page) (page-state page))
    (-back! [_] (.goBack page) (.waitForLoadState page) (page-state page))
    (-scroll! [_ _direction] (page-state page))
    (-state [_] (page-state page))
    (-click! [_ index] (click-nth! page index) (.waitForLoadState page) (page-state page))
    (-input-text! [_ index text] (type-into-nth! page index text) (page-state page))))

(defn playwright-session
  "Launch a Playwright Chromium session. Returns a session map:
    {:browser <IBrowser> :page <Page> :screenshot (fn [path]->path) :close (fn [])}.
  :screenshot writes a full-page PNG to `path` (for recipe verification).
  Options: :headless? (default true), :timeout ms (default 30000)."
  ([start-url] (playwright-session start-url {}))
  ([start-url {:keys [headless? timeout] :or {headless? true timeout 30000}}]
   (let [pw      (Playwright/create)
         browser (-> pw .chromium (.launch (-> (BrowserType$LaunchOptions.)
                                               (.setHeadless headless?))))
         ctx     (.newContext browser)
         page    (.newPage ctx)
         _       (.setDefaultTimeout page timeout)
         _       (when start-url (.navigate page start-url))]
     {:browser (make-browser page)
      :page page
      :screenshot (fn [path]
                    (.screenshot page (-> (Page$ScreenshotOptions.)
                                          (.setPath (Paths/get path (into-array String [])))
                                          (.setFullPage true)))
                    path)
      :select (fn [index value] (select-nth! page index value) (page-state page))
      :check (fn [index] (check-nth! page index) (page-state page))
      :close (fn [] (.close browser) (.close pw))})))

(defn playwright-browser
  "Create an IBrowser backed by a Playwright Chromium session (see
  `playwright-session` for the screenshot/close-capable variant)."
  ([start-url] (playwright-browser start-url {}))
  ([start-url opts] (:browser (playwright-session start-url opts))))
