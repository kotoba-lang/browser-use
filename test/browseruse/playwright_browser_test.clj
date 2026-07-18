(ns browseruse.playwright-browser-test
  (:require [browseruse.browser :as b]
            [browseruse.playwright-browser :as pw]
            [clojure.test :refer [deftest is testing]]))

(def chrome "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

(deftest owned-session-smoke
  (if-not (.exists (java.io.File. chrome))
    (is true "System Chromium unavailable; host smoke is platform-gated")
    (let [session (pw/playwright-session
                   "data:text/html,<title>Host</title><button onclick=\"console.log('clicked')\">Go</button>"
                   {:executable-path chrome :viewport {:width 640 :height 480}})]
      (try
        (testing "evaluate, viewport, screenshot bytes and telemetry"
          (is (= {:title "Host" :width 640}
                 (b/-evaluate! (:browser session) "() => ({title:document.title,width:innerWidth})" nil)))
          (b/-wait! (:browser session) {:condition "() => document.title === 'Host'"})
          (is (pos? (:size (b/-screenshot! (:browser session)
                                           {:path "/private/tmp/browser-use-host-smoke.png"
                                            :format :png :full-page? false}))))
          (b/-click! (:browser session) 0)
          (is (= "clicked" (-> ((:events session)) :console last :text))))
        (testing "owned tabs"
          (let [{:keys [id]} ((:new-tab session) "data:text/html,<title>Second</title>")]
            (is (= "Second" (:title (b/-state (:browser session)))))
            (is (= 2 (count ((:tabs session)))))
            ((:close-tab session) id)
            (is (= 1 (count ((:tabs session)))))))
        (testing "cookies and local storage"
          ((:set-cookies session) [{:name "a" :value "b" :url "https://example.com"}])
          (is (= "b" (-> ((:cookies session)) first :value)))
          ((:clear-cookies session))
          (is (empty? ((:cookies session)))))
        (finally
          ((:close session))
          (is (= {:closed true} ((:close session)))))))))
