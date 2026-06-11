(ns browseruse.agent
  "browser-use agent loop on langgraph-clj:

      :agent → (tool calls?) → :tools → :agent → … → done/END

  The model sees the task plus the indexed-element page state; every
  action result re-embeds the fresh state. The loop ends when the
  model calls the `done` action (or stops calling tools / hits
  :max-steps).

  Datomic premise (ADR-0010): with a :history-conn every executed
  action is recorded as a datom — sessions become a queryable audit
  trail (\"every URL the agent visited\", \"all input_text actions
  yesterday\") instead of a log file."
  (:require [langgraph.graph :as g]
            [langchain.model :as model]
            [langchain.message :as msg]
            [langchain.tool :as tool]
            [langchain.db :as db]
            [browseruse.browser :as b]
            [browseruse.actions :as actions]))

(def default-system-prompt
  (str "You are a browser automation agent. You control a browser via tools.\n"
       "Interactive elements are shown as [index]<tag>text</tag>; refer to them by index.\n"
       "Work step by step toward the user's task. "
       "When the task is complete, call the `done` tool with the result."))

(def log-schema
  "Merge into your db schema for the action log."
  {:session/id     {:db/unique :db.unique/identity}
   :action/session {:db/valueType :db.type/ref}
   :action/step    {}
   :action/name    {}
   :action/input   {}    ; pr-str EDN
   :action/result  {}
   :action/url     {}})

(defn- log-action! [conn db-api session-id step {:keys [name input]} result url]
  (let [{:keys [transact!]} db-api]
    (transact! conn
               [{:session/id session-id}
                {:action/session [:session/id session-id]
                 :action/step step
                 :action/name name
                 :action/input (pr-str input)
                 :action/result (str result)
                 :action/url (str url)}])))

(defn build-agent
  "Compiles the agent graph.

  opts: {:model ChatModel  :browser IBrowser
         :actions [tool…]          ; default: actions/default-actions
         :system \"…\"             ; default: default-system-prompt
         :history-conn conn        ; optional — action log datoms
         :session-id \"…\"         ; action-log session (default \"default\")
         :db-api langchain.db/api
         :max-steps 25
         :compile-opts {…}}        ; extra langgraph compile opts"
  [{:keys [model browser actions system history-conn session-id db-api max-steps compile-opts]
    :or {db-api db/api max-steps 25 session-id "default"}}]
  (let [tools (conj (vec (or actions (actions/default-actions browser)))
                    actions/done-action)
        step-counter (atom 0)
        call-model
        (fn [{:keys [messages]}]
          {:messages [(model/-generate model
                                       (into [(msg/system (or system default-system-prompt))]
                                             messages)
                                       {:tools tools})]})
        run-tools
        (fn [{:keys [messages]}]
          (let [calls (:tool-calls (msg/last-message messages))
                outcome
                (reduce
                 (fn [{:keys [msgs] :as acc} {:keys [name] :as call}]
                   (let [r (tool/execute tools call)
                         step (swap! step-counter inc)]
                     (when history-conn
                       (log-action! history-conn db-api
                                    session-id step call (:content r)
                                    (:url (b/-state browser))))
                     (cond-> (assoc acc :msgs (conj msgs r))
                       (= "done" name) (assoc :done true :result (:text (:input call))))))
                 {:msgs []}
                 calls)]
            (cond-> {:messages (:msgs outcome)}
              (:done outcome) (assoc :done true :result (:result outcome)))))]
    (-> (g/state-graph {:channels {:messages {:reducer (fnil into []) :default []}
                                   :done {:default false}
                                   :result {}}})
        (g/add-node :agent call-model)
        (g/add-node :tools run-tools)
        (g/set-entry-point :agent)
        (g/add-conditional-edges :agent
                                 (fn [{:keys [messages]}]
                                   (if (msg/tool-calls (msg/last-message messages))
                                     :tools
                                     g/END)))
        (g/add-conditional-edges :tools
                                 (fn [{:keys [done]}] (if done g/END :agent)))
        (g/compile-graph (merge {:recursion-limit max-steps} compile-opts)))))

(defn run
  "One-shot: builds the agent and runs a task. Returns
  {:result .. :done bool :messages [..] :steps n}.

  (run {:model m :browser b :task \"Find the pricing page\"
        :history-conn conn :session-id \"s1\"})"
  [{:keys [browser task run-opts] :as opts}]
  (let [agent (build-agent opts)
        first-msg (msg/user (str "Task: " task "\n\n"
                                 (b/state->prompt (b/-state browser))))
        out (g/run* agent {:messages [first-msg]} (or run-opts {}))]
    {:result (:result (:state out))
     :done (boolean (:done (:state out)))
     :messages (:messages (:state out))
     :steps (count (:events out))}))
