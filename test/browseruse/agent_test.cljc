(ns browseruse.agent-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [browseruse.browser :as b]
            [browseruse.actions :as actions]
            [browseruse.agent :as agent]
            [langchain.model :as model]
            [langchain.message :as msg]
            [langchain.db :as db]))

(def site
  {"https://example.com"
   {:title "Example Shop"
    :elements [{:tag "a" :text "Pricing" :nav "https://example.com/pricing"}
               {:tag "input" :attrs {:name "q"}}
               {:tag "button" :text "Search"
                :nav-fn (fn [{:keys [inputs url]}]
                          (str url "/search?q=" (get inputs [url 1])))}]}
   "https://example.com/pricing"
   {:title "Pricing"
    :elements [{:tag "a" :text "Home" :nav "https://example.com"}]}
   "https://example.com/search?q=clojure"
   {:title "Results for clojure"
    :elements []}})

(deftest mock-browser-behaviour
  (let [br (b/mock-browser site "https://example.com")]
    (testing "indexed element state + prompt rendering"
      (let [st (b/-state br)]
        (is (= "Example Shop" (:title st)))
        (is (= [0 1 2] (mapv :index (:elements st))))
        (is (str/includes? (b/state->prompt st) "[0]<a>Pricing</a>"))))
    (testing "click navigates via :nav"
      (is (= "https://example.com/pricing" (:url (b/-click! br 0)))))
    (testing "back"
      (is (= "https://example.com" (:url (b/-back! br)))))
    (testing "typed text + nav-fn computed navigation"
      (b/-input-text! br 1 "clojure")
      (is (str/includes? (b/state->prompt (b/-state br)) "value=\"clojure\""))
      (is (= "Results for clojure" (:title (b/-click! br 2)))))
    (testing "unknown index throws"
      (is (thrown? #?(:clj Exception :cljs js/Error) (b/-click! br 9))))))

(deftest actions-embed-state
  (let [br (b/mock-browser site "https://example.com")
        tools (actions/default-actions br)
        click (some #(when (= "click_element" (:name %)) %) tools)]
    (is (str/includes? ((:fn click) {:index 0}) "Current page: Pricing"))))

(defn- scripted-model
  "Mock model: navigate to pricing → read → done."
  []
  (model/mock-model
   [(msg/ai "" {:tool-calls [{:id "c1" :name "click_element" :input {:index 0}}]})
    (msg/ai "" {:tool-calls [{:id "c2" :name "done"
                              :input {:text "Pricing is on https://example.com/pricing"
                                      :success true}}]})]))

(deftest agent-loop-with-action-log
  (let [br (b/mock-browser site "https://example.com")
        conn (db/create-conn agent/log-schema)
        {:keys [result done messages]}
        (agent/run {:model (scripted-model)
                    :browser br
                    :task "Find the pricing page URL"
                    :history-conn conn
                    :session-id "s1"})]
    (is done)
    (is (= "Pricing is on https://example.com/pricing" result))
    (is (= "https://example.com/pricing" (:url (b/-state br))))
    (testing "conversation shape"
      (is (= [:user :assistant :tool :assistant :tool] (mapv :role messages))))
    (testing "action log is queryable datoms"
      (is (= #{["click_element" "https://example.com/pricing"]
               ["done" "https://example.com/pricing"]}
             (db/q '[:find ?name ?url
                     :in $ ?sid
                     :where [?s :session/id ?sid]
                            [?a :action/session ?s]
                            [?a :action/name ?name]
                            [?a :action/url ?url]]
                   (db/db conn) "s1"))))))

(deftest max-steps-limits-loop
  (let [br (b/mock-browser site "https://example.com")
        ;; model that scrolls forever
        m (model/mock-model
           [(msg/ai "" {:tool-calls [{:id "x" :name "scroll"
                                      :input {:direction "down"}}]})])]
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (agent/run {:model m :browser br :task "loop" :max-steps 4})))))
