(ns compoje.render
  (:require [babashka.fs :as fs]
            [compoje.utils :as u]
            [clojure.tools.logging :as log]
            [selmer.filters :refer [add-filter!]]
            [clj-yaml.core :as yaml]
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

(defn hash-file
  "Hash a file name using the provided digest algorithm.
   Defaults to md5 if not specified."
  ([file-name]
   (hash-file file-name "md5"))
  ([file-name digest]
   (let [digest (or digest "md5")
         contents (slurp file-name)]
     (hash-string contents digest))))

(add-filter! :hash-file hash-file)

(defn file-path
  [args context-map]
  (log/trace "file-path" args "->" context-map)
  (let [{:keys [template-dir]} context-map
        fname (first args)
        fname (u/strip-quotes fname)]
    (str (fs/absolutize (fs/file template-dir fname)))))

(defn file-hash
  [args context-map]
  (log/trace "file-hash" args "->" context-map)
  (let [fname (first args)
        fname (u/strip-quotes fname)
        {:keys [template-dir]} context-map
        f (-> (fs/file template-dir fname)
              fs/absolutize
              fs/file)
        hash (hash-file f "md5")]
    (subs hash 0 6)))

(defn to-yaml
  "Convert context data to formatted yaml and include it in the template."
  [args context-map]
  (let [data-key (first args)
        data (or (get context-map data-key)
                 (get context-map (keyword data-key)))
        content (yaml/generate-string data :dumper-options {:flow-style :block})]
    (tap> {"args:" args
          "context-map" context-map
          "content" content})
    content))

(comment

  (parser/resolve-arg "Hello {{variable}}" {:variable "John"})

  )

(parser/add-tag! :file-path file-path)
(parser/add-tag! :file-hash file-hash)
(parser/add-tag! :to-yaml to-yaml)

(defn load-template
  ([template-file]
   (let [f (fs/file (fs/absolutize template-file))]
     (log/debug "Load template from" (str f))
     (slurp f))))

(defn render
  "Render a stack template.
   Template can access values in context map.
   Result is returned as a string."
  ([template-file context]
   (render template-file context nil))
  ([template-file context {:keys [render-opts]
                           :or {render-opts {:tag-open \< :tag-close \>}}
                           :as _opts}]
   (let [tpl (load-template template-file)
         result (without-escaping
                 (parser/render tpl context render-opts))]
     result)))

(comment

  (println (yaml/generate-string
            [{:name "John Smith", :age 33}
             {:name "Mary Smith", :age 27}]
            :dumper-options {:indent 6
                             :indicator-indent 4
                             :flow-style :block}))

  (without-escaping
   (parser/render "{{x}}" {:x "I <3 NY"}))

  (file-hash ["/home/ieugen/proiecte/clojure/compoje/data/stacks/nginx/stack.tpl.yml" "md5"] {})

  (parser/render "{% file-hash quux %} {% foo baz %}" {})

  (parser/render "{%  quux %} {% foo baz %}" {})

  )