(ns compoje.utils
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]))

(defn pprint-str
  "Pretty print to string"
  [content]
  (let [out (java.io.StringWriter.)]
    (pp/pprint content out)
    (.toString out)))

(defn println-err
  "println to standard error."
  [& more]
  (binding [*out* *err*]
    (apply println more)))

(defn deep-merge
  "From https://clojuredocs.org/clojure.core/merge ."
  [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

(defn strip-quote
  [s quote-char]
  (let [s (if (str/starts-with? s quote-char)
            (subs s 1)
            s)
        s (if (str/ends-with? s quote-char)
            (subs s 0 (dec (count s)))
            s)]
    s))

(defn strip-quotes
  [s]
  (let [s (strip-quote s "\"")
        s (strip-quote s "'")]
    s))

(comment
  (map strip-quotes ["" "\"" "a" "\" a \"" "'a'"])


  0)

(defn kwd-key
  "Convert [\"a.c.d\" \"b\"] to [(:a :c :d) \"b\"].
   Splits key by dot (.) and converts to keywords.
   It will strip quotes on values that start and end with quotes."
  [kv]
  [(map keyword (str/split (first kv) #"\."))
   (strip-quotes (second kv))])

(defn set-args->map
  "Convert vector of k.e.y=value entries to a map with all values merged."
  [set-args]
  (if-not (empty? set-args)
    (let [keys+vals (map #(str/split % #"=" 2) set-args)
          kwd-keys (map kwd-key keys+vals)
          maps (map #(assoc-in {} (first %) (second %)) kwd-keys)]
      (apply deep-merge maps))
    {}))