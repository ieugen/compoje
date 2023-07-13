(ns compoje.core
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [keywordize-keys]]
            [selmer.filters :refer [add-filter!]]
            [selmer.parser :as parser]
            [selmer.util :refer [without-escaping]]))

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
  (log/trace "file-path" args "->" context-map)
  (let [{:keys [template-dir]} context-map
        fname (first args)
        fname (strip-quotes fname)]
    (str (fs/absolutize (fs/file template-dir fname)))))

(defn file-hash
  [args context-map]
  (log/trace "file-hash" args "->" context-map)
  (let [fname (first args)
        fname (strip-quotes fname)
        {:keys [template-dir]} context-map
        f (-> (fs/file template-dir fname)
              fs/absolutize
              fs/file)
        hash (hash-file f "md5")]
    (subs hash 0 6)))

(parser/add-tag! :file-path file-path)
(parser/add-tag! :file-hash file-hash)

(defn deep-merge
  "From https://clojuredocs.org/clojure.core/merge ."
  [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

(defn load-values
  "Load values to use in template file."
  [dir]
  (log/debug "Load values from" (str dir))
  #_(apply deep-merge (map keywordize-keys (map cc/fetch-resource values)))
  {})

(defn load-template
  [dir & {:keys [template-name]
          :or {template-name "stack.tpl.yml"}}]
  (let [f (fs/file dir template-name)
        f (fs/file (fs/absolutize f))]
    (log/debug "Load template from" (str f))
    (slurp f)))

(defn load-context
  "Builds context as a map of values and returns it.
   Merges in values from cli to replace values from files."
  ([dir]
   (load-context dir {}))
  ([dir cli-values]
   ;; TODO: merge cli values
   (let [cwd (System/getProperty "user.dir")]
     {:values (load-values dir)
      :cwd cwd
      :template-dir dir
      :output "stack.generated.yml"})))

(defn render
  "Render a stack template.
   Template can access values in context map.
   Result is returned as a string."
  [dir context {:keys [render-opts]
                :or {render-opts {:tag-open \< :tag-close \>}}
                :as opts}]
  (let [tpl (load-template dir)
        result (without-escaping
                (parser/render tpl context render-opts))]
    result))

(defn render->file
  "Render a stack template to a file.
   Template can access values in context map."
  [dir context file opts]
  (let [tpl (render dir context opts)]
    (log/debug "Writing template to" file)
    (spit file tpl)))

(comment

  (without-escaping
   (parser/render "{{x}}" {:x "I <3 NY"}))

  (file-hash ["/home/ieugen/proiecte/clojure/compoje/data/stacks/nginx/stack.tpl.yml" "md5"] {})

  (parser/render "{% file-hash quux %} {% foo baz %}" {})

  (parser/render "{%  quux %} {% foo baz %}" {})

  )