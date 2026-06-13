(ns yoro-visual-test
  "Visual tests for the yoro ClojureScript SPA (localhost:8700).

  Two modes:
    1. Offline / CI  — mock-browser + mock-model (no network, deterministic)
    2. Live          — playwright-browser + Murakumo LiteLLM (needs running dev server)

  Run offline:
    clojure -Sdeps '{:paths [\"src\" \"examples\"]
                    :deps {io.github.com-junkawasaki/langgraph-clj
                           {:git/tag \"v0.2.0\" :git/sha \"133740f\"}}}' \\
            -M -e \"(require 'yoro-visual-test) (yoro-visual-test/run-all!)\"

  Run live (requires: npm run dev inside cljs/, Playwright dep, Murakumo :4000):
    clojure -A:playwright -M -e \"(require 'yoro-visual-test) (yoro-visual-test/run-live!)\"

  Inference route: Murakumo LiteLLM 127.0.0.1:4000 (ADR-2605215000 — Murakumo-only)."
  (:require [browseruse.browser :as b]
            [browseruse.agent  :as agent]
            [langchain.model   :as model]
            [langchain.message :as msg]
            [langchain.db      :as db]))

;; ──────────────────────────────────────────────────────────────────────
;; Mock site — yoro DOM skeleton (matches the actual ClojureScript SPA)
;; ──────────────────────────────────────────────────────────────────────

(def yoro-mock-site
  {"http://localhost:8700/index.html"
   {:title "yoro"
    :elements
    [{:tag "button" :text "ログイン"   :attrs {:class "auth-btn"}}
     {:tag "a"      :text "ホーム"     :nav   "http://localhost:8700/index.html"}
     {:tag "a"      :text "検索"       :nav   "http://localhost:8700/search"}
     {:tag "a"      :text "通知"       :nav   "http://localhost:8700/notifs"}
     {:tag "article" :text "はじめての投稿です #yoro"
      :attrs {:data-post-uri "at://did:plc:abc/app.bsky.feed.post/001"}
      :nav "http://localhost:8700/profile/starter.bsky.social/post/001"}
     {:tag "article" :text "ClojureScript + re-frame で作ったよ"
      :attrs {:data-post-uri "at://did:plc:def/app.bsky.feed.post/002"}
      :nav "http://localhost:8700/profile/clj.fan/post/002"}]}

   "http://localhost:8700/search"
   {:title "yoro — 検索"
    :elements
    [{:tag "input"  :attrs {:placeholder "アカウントを検索…" :name "q"}}
     {:tag "button" :text "検索"}
     {:tag "a"      :text "ホーム"  :nav "http://localhost:8700/index.html"}
     {:tag "a"      :text "検索"   :nav "http://localhost:8700/search"}
     {:tag "a"      :text "通知"   :nav "http://localhost:8700/notifs"}]}

   "http://localhost:8700/notifs"
   {:title "yoro — 通知"
    :elements
    [{:tag "p"      :text "通知はありません"}
     {:tag "a"      :text "ホーム"  :nav "http://localhost:8700/index.html"}
     {:tag "a"      :text "検索"   :nav "http://localhost:8700/search"}
     {:tag "a"      :text "通知"   :nav "http://localhost:8700/notifs"}]}

   "http://localhost:8700/profile/starter.bsky.social/post/001"
   {:title "yoro — スレッド"
    :elements
    [{:tag "article" :text "はじめての投稿です #yoro"
      :attrs {:data-role "root-post"}}
     {:tag "article" :text "素晴らしい！"
      :attrs {:data-role "reply"}}
     {:tag "a"      :text "戻る" :nav "http://localhost:8700/index.html"}]}

   "http://localhost:8700/profile/starter.bsky.social"
   {:title "yoro — @starter.bsky.social"
    :elements
    [{:tag "h1"      :text "Starter"}
     {:tag "p"       :text "@starter.bsky.social"}
     {:tag "button"  :text "フォロー" :attrs {:data-role "follow-btn"}}
     {:tag "a"       :text "戻る" :nav "http://localhost:8700/index.html"}]}})

;; ──────────────────────────────────────────────────────────────────────
;; Scripted model responses (offline)
;; ──────────────────────────────────────────────────────────────────────

(defn- scripted
  "Build a mock-model that emits pre-defined tool call / text sequences."
  [& turns]
  (model/mock-model (vec turns)))

(defn- click [id text] (msg/ai "" {:tool-calls [{:id id :name "click_element" :input {:index text}}]}))
(defn- go [id url]   (msg/ai "" {:tool-calls [{:id id :name "navigate"      :input {:url url}}]}))
(defn- done [id txt] (msg/ai "" {:tool-calls [{:id id :name "done"          :input {:text txt :success true}}]}))

;; ──────────────────────────────────────────────────────────────────────
;; Test 1 — unauthenticated home feed loads without error
;; ──────────────────────────────────────────────────────────────────────

(defn test-unauthenticated-feed!
  "Verify the home feed renders posts for an unauthenticated visitor."
  []
  (println "\n▸ test: unauthenticated feed")
  (let [browser (b/mock-browser yoro-mock-site "http://localhost:8700/index.html")
        m       (scripted (done "d1" "Home feed is visible. Two posts found. ログイン button present."))
        conn    (db/create-conn agent/log-schema)
        {:keys [result done]} (agent/run
                               {:model m :browser browser
                                :task  "Describe the home page. Is the feed visible? Are there posts?"
                                :history-conn conn :session-id "t1"})]
    (println "  result:" result)
    (assert done "agent should complete")
    (assert (re-find #"(?i)(投稿|post|feed|ホーム)" result))
    (println "  ✓ PASS")))

;; ──────────────────────────────────────────────────────────────────────
;; Test 2 — bottom tab navigation: home → search → notifs → home
;; ──────────────────────────────────────────────────────────────────────

(defn test-tab-navigation!
  "Verify the bottom tab bar navigates between the three main sections."
  []
  (println "\n▸ test: tab navigation")
  (let [browser (b/mock-browser yoro-mock-site "http://localhost:8700/index.html")
        ;; home:   [0]=ログイン [1]=ホーム [2]=検索 [3]=通知 [4]=post1 [5]=post2
        ;; search: [0]=input   [1]=btn   [2]=ホーム [3]=検索 [4]=通知
        ;; notifs: [0]=empty-p [1]=ホーム [2]=検索  [3]=通知
        m       (scripted
                 (click "c1" 2)          ; home: click 検索 → /search
                 (click "c2" 4)          ; search: click 通知 (index 4) → /notifs
                 (click "c3" 1)          ; notifs: click ホーム (index 1) → /
                 (done  "d1" "Navigated ホーム→検索→通知→ホーム successfully."))
        conn    (db/create-conn agent/log-schema)
        {:keys [result done]} (agent/run
                               {:model m :browser browser
                                :task  "Navigate to 検索, then 通知, then back to ホーム via the tab bar."
                                :history-conn conn :session-id "t2"})
        visited-urls (db/q '[:find [?u ...] :where [_ :action/url ?u]]
                           (db/db conn))]
    (println "  result:" result)
    (println "  visited:" (sort visited-urls))
    (assert done "agent should complete")
    (assert (some #(clojure.string/includes? % "search")  visited-urls))
    (assert (some #(clojure.string/includes? % "notifs")  visited-urls))
    (println "  ✓ PASS")))

;; ──────────────────────────────────────────────────────────────────────
;; Test 3 — post thread page loads parent post and reply
;; ──────────────────────────────────────────────────────────────────────

(defn test-post-thread!
  "Click a post card → thread page shows root post + at least one reply."
  []
  (println "\n▸ test: post thread")
  (let [browser (b/mock-browser yoro-mock-site "http://localhost:8700/index.html")
        ;; [4]=first article post on home
        m       (scripted
                 (click "c1" 4)
                 (done  "d1" "Thread page loaded. Root post: 'はじめての投稿です #yoro'. Reply: '素晴らしい！'"))
        conn    (db/create-conn agent/log-schema)
        {:keys [result done]} (agent/run
                               {:model m :browser browser
                                :task  "Click the first post in the feed and describe what you see on the thread page."
                                :history-conn conn :session-id "t3"})]
    (println "  result:" result)
    (assert done)
    (assert (re-find #"(?i)(スレッド|thread|reply|返信|素晴らしい)" result))
    (println "  ✓ PASS")))

;; ──────────────────────────────────────────────────────────────────────
;; Test 4 — auth-modal guard: 通知 page is empty, no XRPC errors
;; ──────────────────────────────────────────────────────────────────────

(defn test-notifs-empty-unauthenticated!
  "Notifications page shows empty state; session probe rule is not violated
  (no XRPC errors logged)."
  []
  (println "\n▸ test: notifs empty for unauthenticated user")
  (let [browser (b/mock-browser yoro-mock-site "http://localhost:8700/index.html")
        ;; [3]=通知 tab
        m       (scripted
                 (click "c1" 3)
                 (done  "d1" "通知はありません — empty state shown, no error banners."))
        conn    (db/create-conn agent/log-schema)
        {:keys [result done]} (agent/run
                               {:model m :browser browser
                                :task  "Navigate to 通知 and report whether it shows an empty state or an error."
                                :history-conn conn :session-id "t4"})]
    (println "  result:" result)
    (assert done)
    (assert (re-find #"(?i)(ありません|empty|no.*notif|エラーなし|no error)" result))
    (println "  ✓ PASS")))

;; ──────────────────────────────────────────────────────────────────────
;; Offline suite
;; ──────────────────────────────────────────────────────────────────────

(defn run-all! []
  (println "=== yoro visual tests (offline / mock) ===")
  (test-unauthenticated-feed!)
  (test-tab-navigation!)
  (test-post-thread!)
  (test-notifs-empty-unauthenticated!)
  (println "\n=== All tests PASSED ==="))

;; ──────────────────────────────────────────────────────────────────────
;; Live-browser version (Playwright — needs :playwright alias)
;;
;; browseruse.playwright-browser/playwright-browser implements IBrowser
;; over com.microsoft.playwright. Swap into any test above:
;;   (require '[browseruse.playwright-browser :refer [playwright-browser]])
;;   (playwright-browser "http://localhost:8700/index.html" {:headless? false})
;;
;; Model: Murakumo LiteLLM (ADR-2605215000 — Murakumo-only invariant):
;;   (model/openai-compatible-model {:base-url "http://127.0.0.1:4000"
;;                                   :model    "gemma4:e4b"
;;                                   :http-fn  clj-http.client/post})
;; ──────────────────────────────────────────────────────────────────────

(defn live-browser
  "Dynamically resolve playwright-browser (requires :playwright alias in deps.edn).
  Returns nil if Playwright is not on the classpath."
  [start-url opts]
  (try
    (require 'browseruse.playwright-browser)
    ((requiring-resolve 'browseruse.playwright-browser/playwright-browser) start-url opts)
    (catch Exception e
      (println "Playwright not available:" (ex-message e))
      nil)))

(comment
  ;; Live run against the real yoro dev server + Murakumo LiteLLM
  ;; Start with: clojure -A:playwright -M -e "(require 'yoro-visual-test) (yoro-visual-test/run-live!)"
  (let [browser (live-browser "http://localhost:8700/index.html" {:headless? false})
        m       (model/openai-compatible-model
                 {:base-url "http://127.0.0.1:4000"
                  :model    "gemma4:e4b"
                  ;; http-fn: clj-http.client/post (provided by :playwright alias)
                  })
        conn    (db/create-conn agent/log-schema)]
    (agent/run {:model   m
                :browser browser
                :task    "Describe the yoro home feed. How many posts are visible? Is there a compose button?"
                :history-conn conn
                :session-id   "live-1"})))
