(ns browseruse.playwright-probe-test
  (:require [browseruse.playwright-probe :as probe]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def chrome "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

(deftest dry-run-redacts-proxy-credentials
  (let [result (probe/run-probe!
                {:probe-url "https://example.test/ip"
                 :user-data-dir "/tmp/browseruse-dry-profile"
                 :proxy {:server "http://proxy.example:8080"
                         :username "probe-user" :password "probe-secret"}
                 :expected {:ip "203.0.113.1"}
                 :dry-run? true})]
    (is (= :dry-run (:status result)))
    (is (= "[REDACTED]" (get-in result [:plan :capabilities :effective-config :proxy :password])))
    (is (not (str/includes? (pr-str result) "probe-secret"))))
  (testing "credentials embedded in URLs are rejected before they can be reported"
    (is (thrown? clojure.lang.ExceptionInfo
                 (probe/validate-probe-config
                  {:probe-url "https://example.test/ip"
                   :user-data-dir "/tmp/profile"
                   :proxy {:server "http://user:secret@proxy.example:8080"}})))))

(deftest deterministic-profile-reopen
  (if-not (.exists (java.io.File. chrome))
    (is true "System Chromium unavailable; live persistence test is platform-gated")
    (let [server (probe/start-deterministic-server!)
          profile-dir (Files/createTempDirectory "browseruse-profile-probe-"
                                                 (make-array FileAttribute 0))]
      (try
        (let [result (probe/run-probe!
                      {:probe-url (:url server)
                       :user-data-dir (str profile-dir)
                       :executable-path chrome
                       :locale "ja-JP"
                       :timezone-id "Asia/Tokyo"
                       :expected {:ip "127.0.0.1"
                                  :locale "ja-JP"
                                  :timezone "Asia/Tokyo"}})]
          (is (= :passed (:status result)))
          (is (every? :pass? (vals (:checks result))))
          (is (= {:cookie? true :local-storage? true} (:persistence result))))
        (finally
          ((:close server)))))))
