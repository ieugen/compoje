(ns compoje.utils
  (:require [clojure.pprint :as pp]))

(defn pprint-str
  "Pretty print to string"
  [content]
  (let [out (java.io.StringWriter.)]
    (pp/pprint content out)
    (.toString out)))