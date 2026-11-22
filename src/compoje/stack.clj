(ns compoje.stack
  "Docker secrets and configs whose content compoje keeps in memory.

   A top-level secret or config definition with an x-compoje key is managed by
   compoje instead of being read from a file by docker:

     secrets:
       db_password:
         x-compoje:
           from: app.db_password        # path in the render context
     configs:
       nginx_conf:
         x-compoje:
           template: config/nginx.conf  # file rendered with the context

   compoje creates the object (name: `name`, or <stack>_<key>-<content hash>) and
   replaces the definition with {external: true, name: ...}, so the content
   never touches the disk. Pure functions; creating the objects is up to the
   deploy engine."
  (:require [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def kinds
  "Top-level stack keys that can hold managed definitions, with their kind."
  {"secrets" :secret "configs" :config})

(def managed-label "com.docker.stack.managed-by")

(defn sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

^:rct/test
(comment
  (sha256-hex "abc") ;=> "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  )

(defn uses-managed-objects?
  "Cheap check on the rendered stack text: does any nested definition carry an
   x-compoje key? Only such stacks need to be parsed and rewritten."
  [stack-text]
  (boolean (re-find #"(?m)^\s+x-compoje\s*:" stack-text)))

^:rct/test
(comment
  (uses-managed-objects? "x-compoje:\n  docker: {}\nservices: {}\n") ;=> false
  (uses-managed-objects? "secrets:\n  s:\n    x-compoje:\n      from: a.b\n") ;=> true
  )

(defn managed-defs
  "[[kind key definition] ...] for every secret/config definition (string keys,
   as parsed from YAML) that has an x-compoje key."
  [stack]
  (for [[top kind] kinds
        [k definition] (get stack top)
        :when (and (map? definition) (contains? definition "x-compoje"))]
    [kind k definition]))

(defn context-value
  "Value at dotted `path` in the (keyword keyed) context. Strings as is, numbers
   and booleans as strings; anything else is an error."
  [context path]
  (let [ks (map keyword (str/split (str path) #"\."))
        v (get-in context ks ::missing)]
    (cond
      (= ::missing v) (throw (ex-info (str "x-compoje from: nothing at context path " path) {:path path}))
      (string? v) v
      (or (number? v) (boolean? v)) (str v)
      :else (throw (ex-info (str "x-compoje from: " path " is not a string (it is a "
                                 (if (map? v) "map; use a longer path" (type v)) ")")
                            {:path path})))))

^:rct/test
(comment
  (context-value {:app {:pw "s3cret" :port 5432}} "app.pw") ;=> "s3cret"
  (context-value {:app {:port 5432}} "app.port") ;=> "5432"
  (context-value {:token "t\n"} "token") ;=> "t\n"
  (try (context-value {:app {}} "app.pw") (catch Exception e (ex-message e)))
  ;=> "x-compoje from: nothing at context path app.pw"
  (try (context-value {:app {:db {:pw "x"}}} "app.db") (catch Exception e (ex-message e)))
  ;=> "x-compoje from: app.db is not a string (it is a map; use a longer path)"
  )

(defn- object-data
  [kind k {:strs [file external] :as definition} {:keys [context render-template]}]
  (let [{:strs [from template] :as xc} (get definition "x-compoje")
        where (str (name kind) " " k)]
    (when-not (map? xc)
      (throw (ex-info (str where ": x-compoje must be a map with from or template") {:key k})))
    (when (or file external)
      (throw (ex-info (str where ": x-compoje can't be combined with file or external") {:key k})))
    (cond
      (and from template) (throw (ex-info (str where ": x-compoje takes from or template, not both") {:key k}))
      from (context-value context from)
      template (render-template template)
      :else (throw (ex-info (str where ": x-compoje needs from or template") {:key k})))))

(def max-name-length
  "Docker rejects secret and config names longer than this."
  64)

(defn object-name
  "Name of a managed object: the definition's `name`, else
   <stack>_<key>-<first 10 hex of sha256(data)>, like docker stack's own
   <stack>_<key> names plus a content hash. Too long names are an error."
  [kind stack-name k definition data]
  (let [n (or (get definition "name")
              (str stack-name "_" k "-" (subs (sha256-hex data) 0 10)))]
    (when (> (count n) max-name-length)
      (throw (ex-info (str (name kind) " " k ": name " n " is longer than " max-name-length
                           " characters. Set a shorter name in the definition.")
                      {:key k :name n})))
    n))

^:rct/test
(comment
  (object-name :secret "web" "db_password" {} "s3cret") ;=> "web_db_password-1ec1c26b50"
  (object-name :secret "web" "db_password" {"name" "db-v3"} "s3cret") ;=> "db-v3"
  (try (object-name :config (apply str (repeat 50 "s")) "app_conf" {} "x") (catch Exception e (ex-message e)))
  ;=> "config app_conf: name ssssssssssssssssssssssssssssssssssssssssssssssssss_app_conf-2d711642b7 is longer than 64 characters. Set a shorter name in the definition."
  )

(defn managed-objects
  "The objects compoje must create for `stack` (parsed rendered stack, string
   keys): [{:kind :key :name :data :labels}].
   `opts`: :context (render context), :stack-name, and :render-template, a
   fn from a template path to its rendered text."
  [stack {:keys [stack-name] :as opts}]
  (vec
   (for [[kind k definition] (managed-defs stack)]
     (let [data (object-data kind k definition opts)
           object-name (object-name kind stack-name k definition data)]
       {:kind kind
        :key k
        :name object-name
        :data data
        :labels (merge (get definition "labels")
                       {"com.docker.stack.namespace" stack-name
                        managed-label "compoje"})}))))

^:rct/test
(comment
  (map #(dissoc % :data)
       (managed-objects {"secrets" {"pw" {"x-compoje" {"from" "app.pw"}}
                                    "plain" {"file" "pw.txt"}}
                         "configs" {"conf" {"name" "conf-v2"
                                            "x-compoje" {"template" "c.conf"}}}}
                        {:context {:app {:pw "s3cret"}}
                         :stack-name "web"
                         :render-template (fn [p] (str "rendered " p))}))
  ;=> [{:kind :secret :key "pw" :name "web_pw-1ec1c26b50" :labels {"com.docker.stack.namespace" "web" "com.docker.stack.managed-by" "compoje"}} {:kind :config :key "conf" :name "conf-v2" :labels {"com.docker.stack.namespace" "web" "com.docker.stack.managed-by" "compoje"}}]
  (try (managed-objects {"secrets" {"pw" {"file" "x" "x-compoje" {"from" "a"}}}} {})
       (catch Exception e (ex-message e)))
  ;=> "secret pw: x-compoje can't be combined with file or external"
  )

(defn externalize
  "Replace the managed definitions in `stack` with {external: true, name: ...}."
  [stack objects]
  (reduce (fn [s {:keys [kind key name]}]
            (assoc-in s [(if (= :secret kind) "secrets" "configs") key]
                      {"external" true "name" name}))
          stack
          objects))

^:rct/test
(comment
  (externalize {"secrets" {"pw" {"x-compoje" {"from" "a"}} "f" {"file" "x"}}}
               [{:kind :secret :key "pw" :name "pw-1"}])
  ;=> {"secrets" {"pw" {"external" true "name" "pw-1"} "f" {"file" "x"}}}
  )
