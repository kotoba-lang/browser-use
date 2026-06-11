(ns browseruse.browser
  "Browser abstraction — the host-capability boundary.

  The real browser (Playwright/CDP on the JVM, the page itself on a
  WASM-in-browser host, claude-in-chrome-style MCP on a desktop) is
  injected as an IBrowser implementation; the library only consumes
  the protocol. `mock-browser` provides a pure-data in-memory site
  model for tests and offline runs.

  Browser state follows browser-use's representation: the page as a
  list of *indexed interactive elements* the model can refer to by
  number."
  (:require [clojure.string :as str]))

(defprotocol IBrowser
  (-navigate! [b url])
  (-click! [b index])
  (-input-text! [b index text])
  (-scroll! [b direction])
  (-back! [b])
  (-state [b]
    "→ {:url .. :title .. :elements [{:index 0 :tag \"a\" :text .. :attrs {..}} …]}"))

(defn state->prompt
  "Renders browser state the way browser-use shows it to the model:

     Current page: Example (https://example.com)
     Interactive elements:
     [0]<a>Sign in</a>
     [1]<input name=\"q\" value=\"\">"
  [{:keys [url title elements]}]
  (str "Current page: " title " (" url ")\n"
       "Interactive elements:\n"
       (str/join "\n"
                 (for [{:keys [index tag text attrs]} elements]
                   (str "[" index "]<" tag
                        (apply str (for [[k v] (sort-by key attrs)]
                                     (str " " (name k) "=\"" v "\"")))
                        ">" (or text "") "</" tag ">")))))

;; ───────────────────────── mock browser ─────────────────────────

(defn- indexed-elements [page inputs url]
  (vec (map-indexed
        (fn [i el]
          {:index i
           :tag (:tag el)
           :text (:text el)
           :attrs (cond-> (or (:attrs el) {})
                    (get inputs [url i]) (assoc :value (get inputs [url i])))})
        (:elements page))))

(defn mock-browser
  "In-memory site for tests/offline runs.

  site: {url {:title \"…\"
              :elements [{:tag \"a\" :text \"Sign in\" :nav \"/login\"}
                         {:tag \"input\" :attrs {:name \"q\"}}
                         {:tag \"button\" :text \"Search\"
                          :nav-fn (fn [{:keys [inputs url]}] …)}]}}

  Clicking an element with :nav navigates there; :nav-fn computes the
  target from the browser state (e.g. a search box). Typed text shows
  up as the element's value attribute."
  [site start-url]
  (let [s (atom {:url start-url :inputs {} :history []})]
    (reify IBrowser
      (-navigate! [b url]
        (swap! s (fn [st] (-> st (update :history conj (:url st)) (assoc :url url))))
        (-state b))
      (-click! [b index]
        (let [{:keys [url] :as st} @s
              el (get-in site [url :elements index])]
          (when-not el
            (throw (ex-info "No element at index" {:index index :url url})))
          (when-let [target (or (:nav el)
                                (when-let [f (:nav-fn el)] (f st)))]
            (-navigate! b target))
          (-state b)))
      (-input-text! [b index text]
        (let [{:keys [url]} @s]
          (when-not (get-in site [url :elements index])
            (throw (ex-info "No element at index" {:index index :url url})))
          (swap! s assoc-in [:inputs [url index]] text)
          (-state b)))
      (-scroll! [b _direction] (-state b))
      (-back! [b]
        (swap! s (fn [{:keys [history] :as st}]
                   (if (seq history)
                     (assoc st :url (peek history) :history (pop history))
                     st)))
        (-state b))
      (-state [_]
        (let [{:keys [url inputs]} @s
              page (get site url {:title "404 Not Found" :elements []})]
          {:url url
           :title (:title page)
           :elements (indexed-elements page inputs url)})))))
