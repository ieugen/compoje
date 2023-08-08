(ns compoje.utils
  (:require [clojure.pprint :as pp]))

(defn pprint-str
  "Pretty print to string"
  [content]
  (let [out (java.io.StringWriter.)]
    (pp/pprint content out)
    (.toString out)))

(defn deep-merge
  "From https://clojuredocs.org/clojure.core/merge ."
  [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))