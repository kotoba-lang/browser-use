(ns browseruse.playwright-browser
  "IBrowser implementation over Microsoft Playwright (JVM-only).

  Loaded only when com.microsoft.playwright/playwright is on the classpath
  (see :playwright alias in deps.edn). Do not require from .cljc namespaces.

  Inference route for the model: Murakumo LiteLLM 127.0.0.1:4000 (ADR-2605215000)."
  (:require [browseruse.browser :as b])
  (:import [com.microsoft.playwright Playwright BrowserType$LaunchOptions]))

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
            "               placeholder: el.placeholder || '',"
            "               value: el.value || '',"
            "               href:  el.href  || '' }})); }"))
      (js->clj :keywordize-keys true)))

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
                  "  if (els[" index "]) els[" index "].focus(); }"))
  (.type (.keyboard page) text))

(defn playwright-browser
  "Create an IBrowser backed by a Playwright Chromium session.

  (playwright-browser \"http://localhost:8700/index.html\")

  Options:
    :headless? true/false (default true)
    :timeout   ms (default 30000)"
  ([start-url] (playwright-browser start-url {}))
  ([start-url {:keys [headless? timeout] :or {headless? true timeout 30000}}]
   (let [pw      (Playwright/create)
         browser (-> pw .chromium (.launch (-> (BrowserType$LaunchOptions.)
                                               (.setHeadless headless?))))
         ctx     (.newContext browser)
         page    (.newPage ctx)
         _       (.setDefaultTimeout page timeout)
         _       (.navigate page start-url)]
     (reify b/IBrowser
       (-navigate! [_ url]
         (.navigate page url)
         (.waitForLoadState page)
         (page-state page))
       (-back! [_]
         (.goBack page)
         (.waitForLoadState page)
         (page-state page))
       (-scroll! [_ direction]
         (case direction
           :down (.keyboard page) ; scroll via keyboard for SPA compatibility
           :up   (.keyboard page))
         (page-state page))
       (-state [_]
         (page-state page))
       (-click! [_ index]
         (click-nth! page index)
         (.waitForLoadState page)
         (page-state page))
       (-input-text! [_ index text]
         (type-into-nth! page index text)
         (page-state page))))))
