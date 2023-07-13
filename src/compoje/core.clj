(ns compoje.core
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.walk :refer [keywordize-keys]]
            [babashka.fs :as fs]
            [selmer.util :refer [without-escaping]]
            [selmer.parser :refer [render add-tag!]]
            [selmer.filters :refer [add-filter!]]))

(defn hash-string
  "Hash a string using the provided hash algorithm (MD5, SHA1, etc)."
  [^String s ^String digest]
  (->> s
       .getBytes
       (.digest (java.security.MessageDigest/getInstance digest))
       (BigInteger. 1)
       (format "%032x")))

(defn ^:private hash-file
  "Hash a file name using the provided digest algorithm.
   Defaults to md5 if not specified."
  [file-name digest]
  (let [digest (or digest "md5")
        contents (slurp file-name)]
    (hash-string contents digest)))

(add-filter! :hash-file hash-file)

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
  )

(defn file-path
  [args context-map]
  (println "file-path" args "->" context-map)
  (let [{:keys [cwd]} context-map
        fname (first args)
        fname (strip-quotes fname)]
    (str (fs/file cwd fname)))
  #_(str "file-path>>" (first args)))

(defn file-hash
  [args context-map]
  (println "file-hash" args "->" context-map)
  (let [fname (first args)
        fname (strip-quotes fname)
        {:keys [template-dir]} context-map
        f (-> (fs/file template-dir fname)
              fs/absolutize
              fs/file)
        hash (hash-file f "md5")]
    (subs hash 0 6))
  #_(str "file-hash>>" (first args)))

(add-tag! :file-path file-path)
(add-tag! :file-hash file-hash)

(defn deep-merge
  "From https://clojuredocs.org/clojure.core/merge ."
  [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

(defn load-values
  "Load values to use in template file."
  [dir]
  (println "Load values from" dir)
  #_(apply deep-merge (map keywordize-keys (map cc/fetch-resource values)))
  {})

(defn load-template
  [dir & {:keys [template-name]
          :or {template-name "stack.tpl.yml"}}]
  (let [f (fs/file dir template-name)
        f (fs/file (fs/absolutize f))]
    (println "Load template from" f)
    (slurp f)))

(defn render-tpl
  [dir {:keys [output
               render-opts]
        :or {render-opts {:tag-open \< :tag-close \>}
             output "stack.generated.yml"}
        :as arg-map}]
  (let [cwd (System/getProperty "user.dir")
        context {:values (load-values dir)
                 :cwd cwd
                 :template-dir dir}
        tpl (load-template dir)
        out (fs/file cwd output)
        result (without-escaping
                (render tpl context render-opts))]
    (println arg-map)
    (println out)
    (if (str/blank? output)
      (do
        ;; (log "Write result to stdout.")
        (println result))
      (do
        (println "Write result to'" out "'file")
        (spit out result)))))

(comment

  (without-escaping
   (render "{{x}}" {:x "I <3 NY"}))

  (file-hash ["/home/ieugen/proiecte/clojure/compoje/data/stacks/nginx/stack.tpl.yml" "md5"] {})

  (render "{% file-hash quux %} {% foo baz %}" {} )

  (render "{%  quux %} {% foo baz %}" {})



  )