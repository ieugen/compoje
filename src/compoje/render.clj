(ns compoje.render
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [compoje.files :as files]
            [compoje.utils :as u]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [selmer.filters :refer [add-filter!]]
            [yamlstar.core :as yaml]
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
         contents (files/read-file file-name)]
     (hash-string contents digest))))

(add-filter! :hash-file hash-file)

(defn escape-dollar
  "Escape $ as $$, so docker's compose variable substitution (both engines)
   gives back the value as it is: `<{ app.password|escape-dollar }>`."
  [v]
  (when (some? v) (str/replace (str v) "$" "$$")))

^:rct/test
(comment
  (escape-dollar "pa$word ${HOME}") ;=> "pa$$word $${HOME}"
  (escape-dollar 42) ;=> "42"
  (escape-dollar nil) ;=> nil
  )

(add-filter! :escape-dollar escape-dollar)

(defn yaml-str
  "Render a value as a double-quoted YAML scalar (JSON string syntax, which is
   valid YAML), with $ escaped: survives :, #, quotes, newlines and docker's
   variable substitution. `password: <{ app.password|yaml-str }>`."
  [v]
  (when (some? v) (json/generate-string (escape-dollar v))))

^:rct/test
(comment
  (yaml-str "pa$word: x#y \"q\"") ;=> "\"pa$$word: x#y \\\"q\\\"\""
  (yaml-str "line1\nline2") ;=> "\"line1\\nline2\""
  (yaml-str 5) ;=> "\"5\""
  )

(add-filter! :yaml-str yaml-str)

(defn yaml-flow
  "Render any value (map, list, scalar) as one-line flow YAML (JSON syntax),
   with $ escaped in every string, keys included. One line, so it fits at
   any indentation: `environment: <{ secrets.app|yaml }>`."
  [v]
  (json/generate-string
   (walk/postwalk #(if (string? %) (escape-dollar %) %) v)))

^:rct/test
(comment
  (yaml-flow {"DB_USER" "app" "DB_PASSWORD" "pa$w"}) ;=> "{\"DB_USER\":\"app\",\"DB_PASSWORD\":\"pa$$w\"}"
  (yaml-flow {:db {:tags ["a$" "b"]}}) ;=> "{\"db\":{\"tags\":[\"a$$\",\"b\"]}}"
  (yaml-flow {"$K" 1}) ;=> "{\"$$K\":1}"
  (yaml-flow ["x" 1 true]) ;=> "[\"x\",1,true]"
  (yaml-flow 5) ;=> "5"
  (yaml-flow nil) ;=> "null"
  )

(add-filter! :yaml yaml-flow)

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
        content (yaml/dump data)]
    (tap> {"args:" args
           "context-map" context-map
           "content" content})
    content))

(comment

  (parser/resolve-arg "Hello {{variable}}" {:variable "John"}))

(parser/add-tag! :file-path file-path)
(parser/add-tag! :file-hash file-hash)
(parser/add-tag! :to-yaml to-yaml)

(defn template-variables
  "Variables used in <{ ... }> tags of template text:
   [{:path [\"a\" \"b\"] :expr \"a.b|upper\" :default? bool}]. Variables bound by
   <% for x in ... %> (and their sub-paths) are left out."
  [text]
  (let [loop-vars (set (mapcat (fn [[_ vars]] (map str/trim (str/split vars #",")))
                               (re-seq #"<%\s*for\s+([\w\-,\s]+?)\s+in\s" text)))]
    (->> (re-seq #"<\{\s*(.*?)\s*\}>" text)
         (keep (fn [[_ expr]]
                 (let [var-part (first (str/split expr #"\|"))
                       path (str/split (str/trim var-part) #"\.")]
                   (when (and (re-matches #"[\w\-.]+" (str/trim var-part))
                              (not (loop-vars (first path)))
                              (not (#{"forloop"} (first path))))
                     {:path path :expr expr :default? (boolean (re-find #"\|\s*default" expr))}))))
         distinct)))

^:rct/test
(comment
  (map :path (template-variables "a <{ values.port }> <{ app.pw|yaml-str }> <% for p in values.ports %><{ p }><% endfor %>"))
  ;=> [["values" "port"] ["app" "pw"]]
  (:default? (first (template-variables "<{ values.x|default:\"1\" }>"))) ;=> true
  (template-variables "<{ \"literal\" }>") ;=> []
  )

(defn- lookup [context path]
  (reduce (fn [v seg]
            (cond
              (map? v) (if (contains? v (keyword seg)) (get v (keyword seg)) (reduced ::missing))
              (and (sequential? v) (re-matches #"\d+" seg) (< (parse-long seg) (count v))) (nth v (parse-long seg))
              :else (reduced ::missing)))
          context path))

(defn undefined-variables
  "Variables of template `text` that `context` doesn't define (they render as
   empty strings). Variables with a `default` filter are left out."
  [text context]
  (->> (template-variables text)
       (remove :default?)
       (filter #(contains? #{::missing nil} (lookup context (:path %))))
       (mapv #(str/join "." (:path %)))))

^:rct/test
(comment
  (undefined-variables "<{ values.port }> <{ secrets.cloud.K|yaml-str }> <{ cloud.K }> <{ values.x|default:\"1\" }> <{ ports.0 }>"
                       {:values {:port 80} :secrets [{:name "cloud"}] :cloud {:K "s"} :ports [1]})
  ;=> ["secrets.cloud.K"]
  )

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

  (println (yaml/dump
            [{:name "John Smith", :age 33}
             {:name "Mary Smith", :age 27}]))

  (without-escaping
   (parser/render "{{x}}" {:x "I <3 NY"}))

  (file-hash ["/home/ieugen/proiecte/clojure/compoje/data/stacks/nginx/stack.tpl.yml" "md5"] {})

  (parser/render "{% file-hash quux %} {% foo baz %}" {})

  (parser/render "{%  quux %} {% foo baz %}" {}))