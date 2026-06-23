(ns browseruse.recipe-test
  (:require [clojure.test :refer [deftest is]]
            [browseruse.browser :as b]
            [browseruse.recipe :as recipe]))

(def site
  {"https://site/register"
   {:title "Register"
    :elements [{:tag "input"  :attrs {:name "name" :placeholder "Username"}}
               {:tag "input"  :attrs {:name "mail" :placeholder "E-mail address"}}
               {:tag "input"  :attrs {:name "pass[pass1]" :placeholder "Password"}}
               {:tag "input"  :attrs {:name "pass[pass2]" :placeholder "Confirm password"}}
               {:tag "button" :text "Create new account"}]}})

(deftest match-index-by-name-text-occurrence
  (let [els (:elements (b/-state (b/mock-browser site "https://site/register")))]
    (is (= 0 (recipe/match-index els {:tag "input" :name "name"})))
    (is (= 1 (recipe/match-index els {:placeholder "e-mail"})))
    (is (= 4 (recipe/match-index els {:tag "button" :text "create new account"})))
    (is (= 2 (recipe/match-index els {:tag "input" :occurrence 2})) "3rd input")
    (is (nil? (recipe/match-index els {:tag "input" :name "nope"})))))

(deftest run-fills-and-clicks-with-secret
  (let [browser (b/mock-browser site "https://site/register")
        recipe {:steps [{:do :assert :match {:tag "input" :name "name"}}
                        {:do :fill :match {:tag "input" :name "name"} :value "etzhayyim"}
                        {:do :fill :match {:placeholder "e-mail"} :value "epo@etzhayyim.com"}
                        {:do :fill :match {:tag "input" :name "pass[pass1]"} :value {:secret "vault/pw"}}
                        {:do :screenshot :as "filled"}
                        {:do :wait-human :prompt "captcha"}
                        {:do :click :match {:tag "button" :text "Create new account"}}]}
        shots (atom []) paused (atom [])
        r (recipe/run-recipe! browser recipe
                       {:resolve-secret (fn [ref] (is (= "vault/pw" ref)) "S3cret!")
                        :screenshot (fn [n] (swap! shots conj n) (str "/tmp/" n ".png"))
                        :pause (fn [p] (swap! paused conj p) true)})
        st (b/-state browser)
        v (fn [i] (:value (:attrs (nth (:elements st) i))))]
    (is (:ok r))
    (is (= 7 (count (:log r))))
    (is (= "etzhayyim" (v 0)))
    (is (= "epo@etzhayyim.com" (v 1)))
    (is (= "S3cret!" (v 2)) "secret resolved + typed")
    (is (= ["filled"] @shots))
    (is (= ["captcha"] @paused))
    ;; the secret step is flagged but the value is not stored in the log
    (let [fill-secret (first (filter #(:secret? %) (:log r)))]
      (is (:secret? fill-secret))
      (is (not (contains? fill-secret :value))))))

(deftest run-stops-on-unmatched
  (let [browser (b/mock-browser site "https://site/register")
        r (recipe/run-recipe! browser
                       {:steps [{:do :fill :match {:tag "input" :name "name"} :value "x"}
                                {:do :click :match {:tag "button" :text "Nonexistent"}}
                                {:do :fill :match {:tag "input" :name "mail"} :value "should-not-run"}]}
                       {})]
    (is (not (:ok r)))
    (is (re-find #"no element matched" (:error r)))
    (is (= 1 (count (:log r))) "stopped before the unmatched step; later steps skipped")))
