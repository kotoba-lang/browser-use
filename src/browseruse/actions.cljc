(ns browseruse.actions
  "Default action registry — browser-use's Controller. Each action is
  a langchain tool closing over the browser; every action result
  embeds the fresh page state so the model always sees where it is."
  (:require [browseruse.browser :as b]))

(defn- result [state & [prefix]]
  (str (when prefix (str prefix "\n")) (b/state->prompt state)))

(defn default-actions
  "Standard tool set over an IBrowser. Extend by concatenating your
  own langchain tool maps (a custom \"controller\")."
  [browser]
  [{:name "navigate"
    :description "Navigate to a URL."
    :schema {:type "object"
             :properties {:url {:type "string"}}
             :required ["url"]}
    :fn (fn [{:keys [url]}] (result (b/-navigate! browser url) (str "Navigated to " url)))}

   {:name "click_element"
    :description "Click the interactive element with the given index."
    :schema {:type "object"
             :properties {:index {:type "integer"}}
             :required ["index"]}
    :fn (fn [{:keys [index]}] (result (b/-click! browser index) (str "Clicked [" index "]")))}

   {:name "input_text"
    :description "Type text into the input element with the given index."
    :schema {:type "object"
             :properties {:index {:type "integer"}
                          :text {:type "string"}}
             :required ["index" "text"]}
    :fn (fn [{:keys [index text]}]
          (result (b/-input-text! browser index text)
                  (str "Typed into [" index "]")))}

   {:name "scroll"
    :description "Scroll the page up or down."
    :schema {:type "object"
             :properties {:direction {:type "string" :enum ["up" "down"]}}
             :required ["direction"]}
    :fn (fn [{:keys [direction]}]
          (result (b/-scroll! browser (keyword direction)) (str "Scrolled " direction)))}

   {:name "go_back"
    :description "Go back to the previous page."
    :schema {:type "object" :properties {}}
    :fn (fn [_] (result (b/-back! browser) "Went back"))}])

(def done-action
  "Terminal action — the agent calls this when the task is complete.
  Handled specially by browseruse.agent (ends the loop)."
  {:name "done"
   :description "Call when the task is complete. Provide the final answer/result as text."
   :schema {:type "object"
            :properties {:text {:type "string"}
                         :success {:type "boolean"}}
            :required ["text"]}
   :fn (fn [{:keys [text]}] text)})
