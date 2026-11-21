(ns compoje.migrate
  "Migrate a stack directory from the old layout (stack.tpl.yml + compoje.edn)
   to a single stack.tpl.yml with an x-compoje: section.

   compoje.edn was read with aero. Aero tags (#env, #or, #profile, ...) have no
   equivalent in x-compoje, so migration stops and lists them instead of baking
   machine-specific or secret values into the stack file."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [yamlstar.core :as yaml]))

(defn read-edn-config
  "Read compoje.edn text. Tagged literals (aero tags) are kept as
   clojure.lang.TaggedLiteral values instead of failing the read."
  [s]
  (edn/read-string {:default tagged-literal} s))

^:rct/test
(comment
  (read-edn-config "{:a 1}") ;=> {:a 1}
  (tagged-literal? (:a (read-edn-config "{:a #env \"X\"}"))) ;=> true
  )

(defn find-tags
  "Find tagged literals in `data`. Return one map per (outermost) tag:
   {:path [...] :tag symbol :form \"#tag ...\"}. Paths use keys for maps and
   indexes for sequential collections."
  ([data] (find-tags [] data))
  ([path data]
   (cond
     (tagged-literal? data) [{:path path :tag (:tag data) :form (pr-str data)}]
     (map? data) (vec (mapcat (fn [[k v]] (find-tags (conj path k) v)) data))
     (sequential? data) (vec (mapcat (fn [i v] (find-tags (conj path i) v)) (range) data))
     (set? data) (vec (mapcat #(find-tags (conj path %) %) data))
     :else [])))

^:rct/test
(comment
  (find-tags {:a 1 :b [2 3]}) ;=> []
  (find-tags (read-edn-config "{:p [{:addr #or [#env \"A\" \"b\"]}] :s #env \"S\"}"))
  ;=> [{:path [:p 0 :addr] :tag or :form "#or [#env \"A\" \"b\"]"} {:path [:s] :tag env :form "#env \"S\""}]
  )

(defn- ->str [x]
  (if (keyword? x) (subs (str x) 1) (str x)))

(defn ->yaml-data
  "Convert EDN config data to data that dumps as plain YAML:
   keywords and symbols become strings (:a/b -> \"a/b\"), map keys included;
   sets become vectors."
  [data]
  (cond
    (map? data) (into {} (map (fn [[k v]] [(->str k) (->yaml-data v)])) data)
    (or (sequential? data) (set? data)) (mapv ->yaml-data data)
    (or (keyword? data) (symbol? data)) (->str data)
    :else data))

^:rct/test
(comment
  (->yaml-data {:type :compoje.providers.vault/vault :name :my-vault :n 1 :s "x" :b false})
  ;=> {"type" "compoje.providers.vault/vault" "name" "my-vault" "n" 1 "s" "x" "b" false}
  (->yaml-data {:xs [:a #{:b}] :nil nil}) ;=> {"xs" ["a" ["b"]] "nil" nil}
  )

(def key-order
  "Order of the well-known top-level keys in the generated x-compoje section."
  ["docker" "providers" "secrets" "values"])

(defn order-keys
  "Return map `m` as an ordered map: keys from `key-order` first, then the rest sorted."
  [m]
  (let [known (filter #(contains? m %) key-order)
        rest-keys (sort (remove (set key-order) (keys m)))]
    (apply array-map (mapcat (juxt identity m) (concat known rest-keys)))))

^:rct/test
(comment
  (keys (order-keys {"z" 1 "values" 2 "a" 3 "docker" 4})) ;=> ("docker" "values" "a" "z")
  )

(defn has-compoje-key?
  "True when the template text already has a top-level x-compoje: (or legacy compoje:) key."
  [template]
  (boolean (re-find #"(?m)^(x-)?compoje\s*:" template)))

^:rct/test
(comment
  (has-compoje-key? "services:\n  a: 1\n") ;=> false
  (has-compoje-key? "x-compoje:\n  docker: {}\n") ;=> true
  (has-compoje-key? "version: '3'\ncompoje:\n") ;=> true
  (has-compoje-key? "services:\n  compoje:\n") ;=> false
  )

(defn migrate-template
  "Return the template text with an x-compoje: section built from `config`
   (compoje.edn data) added at the top. The template itself is kept as is:
   it is not parsed, so comments and Selmer tags are preserved."
  [template config]
  (str (yaml/dump {"x-compoje" (order-keys (->yaml-data config))})
       "\n"
       template))

^:rct/test
(comment
  (migrate-template "services: {}\n" {:docker {:stack "s"} :values {:n 1}})
  ;=> "x-compoje:\n  docker:\n    stack: s\n  values:\n    n: 1\n\nservices: {}\n"
  )

(defn- tags-message [tags]
  (str/join "\n"
            (concat ["compoje.edn uses aero tags, which x-compoje cannot express:"]
                    (map (fn [{:keys [path form]}] (str "  " (pr-str path) " " form)) tags)
                    ["Replace each one with a literal value in compoje.edn (and use --set, or a secret"
                     "provider, for values that differ per environment), then run migrate again."])))

(defn migrate!
  "Migrate directory `dir`: add compoje.edn's config to <dir>/stack.tpl.yml as an
   x-compoje: section. compoje.edn is not touched.

   With :dry-run, return the new template text without writing it.
   Otherwise overwrite stack.tpl.yml and return its path.
   Throws ex-info when files are missing, compoje.edn has aero tags, or the
   template already has an x-compoje section."
  [dir {:keys [dry-run]}]
  (let [template-file (fs/file dir "stack.tpl.yml")
        edn-file (fs/file dir "compoje.edn")]
    (when-not (fs/directory? dir)
      (throw (ex-info (str "Not a directory: " dir) {:dir (str dir)})))
    (doseq [f [template-file edn-file]]
      (when-not (fs/exists? f)
        (throw (ex-info (str "Missing " f) {:dir (str dir)}))))
    (let [template (slurp template-file)
          config (read-edn-config (slurp edn-file))
          tags (find-tags config)]
      (when (seq tags)
        (throw (ex-info (tags-message tags) {:file (str edn-file)})))
      (when-not (map? config)
        (throw (ex-info (str edn-file " does not contain a map") {:file (str edn-file)})))
      (when (has-compoje-key? template)
        (throw (ex-info (str template-file " already has an x-compoje section. Nothing to migrate.") {:file (str template-file)})))
      (let [result (migrate-template template config)]
        (if dry-run
          result
          (do
            (spit template-file result)
            (log/debug "Wrote" (str template-file))
            (str template-file)))))))
