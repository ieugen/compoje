(ns compoje.deploy.interpolate
  "Compose variable substitution, as `docker stack deploy` does it, for the
   docker-api engine: $VAR, ${VAR}, ${VAR:-default}, ${VAR-default},
   ${VAR:?error}, ${VAR?error}; $$ is a literal $. Only values are
   substituted, not keys. An unset variable becomes empty (with a warning);
   other ${...} forms are an error, like in docker."
  (:require [clojure.string :as str]))

(def ^:private name-re "[A-Za-z_][A-Za-z0-9_]*")

(def ^:private token-re
  (re-pattern (str "\\$\\$|\\$(" name-re ")|\\$\\{([^}]*)\\}|\\$")))

(defn- substitute [path s getenv warn]
  (str/replace
   s token-re
   (fn [[m bare braced]]
     (cond
       (= "$$" m) "$"
       bare (or (getenv bare) (do (warn bare) ""))
       braced
       (let [[_ n op arg] (or (re-matches (re-pattern (str "(" name-re ")(?:(:-|-|:\\?|\\?)(.*))?")) braced)
                              (throw (ex-info (str "invalid interpolation format for " path ": \"" s "\"."
                                                   " You may need to escape any $ with another $.")
                                              {:path path :value s})))
             v (getenv n)
             empty? (str/blank? (str v))]
         (case op
           nil (or v (do (warn n) ""))
           ":-" (if empty? arg v)
           "-" (if (nil? v) arg v)
           ":?" (if empty? (throw (ex-info (str "required variable " n " is missing a value: " arg) {:path path})) v)
           "?" (if (nil? v) (throw (ex-info (str "required variable " n " is missing a value: " arg) {:path path})) v)))
       :else (throw (ex-info (str "invalid interpolation format for " path ": \"" s "\"."
                                  " You may need to escape any $ with another $.")
                             {:path path :value s}))))))

(defn interpolate
  "Substitute variables in every string value of `data` (keys untouched).
   `getenv`: fn of a variable name; `warn`: called with unset names."
  ([data getenv] (interpolate data getenv (fn [_])))
  ([data getenv warn]
   (letfn [(walk [path x]
             (cond
               (string? x) (if (str/includes? x "$") (substitute (str/join "." path) x getenv warn) x)
               (map? x) (into (empty x) (map (fn [[k v]] [k (walk (conj path k) v)])) x)
               (sequential? x) (vec (map-indexed (fn [i v] (walk (conj path i) v)) x))
               :else x))]
     (walk [] data))))

^:rct/test
(comment
  (interpolate {"image" "nginx:${TAG}"
                "environment" {"A" "$SET" "B" "${UNSET:-dflt}" "C" "${EMPTY:-dflt}" "D" "${EMPTY-dflt}"
                               "E" "price $$5" "G" "${UNSET}x" "H" "${SET}${SET}" "${K}" "key kept"}
                "n" 1}
               {"TAG" "1.27" "SET" "s" "EMPTY" ""})
  ;=> {"image" "nginx:1.27" "environment" {"A" "s" "B" "dflt" "C" "dflt" "D" "" "E" "price $5" "G" "x" "H" "ss" "${K}" "key kept"} "n" 1}
  (try (interpolate {"image" "${REQ:?needs REQ}"} {}) (catch Exception e (ex-message e)))
  ;=> "required variable REQ is missing a value: needs REQ"
  (try (interpolate {"a" {"b" "${SET:+alt}"}} {"SET" "s"}) (catch Exception e (ex-message e)))
  ;=> "invalid interpolation format for a.b: \"${SET:+alt}\". You may need to escape any $ with another $."
  (let [unset (atom [])] (interpolate {"a" "$X ${Y}"} {} #(swap! unset conj %)) @unset) ;=> ["X" "Y"]
  )
