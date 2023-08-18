(ns compoje.context
  "Functions to manage context"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compoje.utils :as u]
            [babashka.fs :as fs]))

;; TODO: consider whether we should use directory name as default stack name
#_(defn context-with-stack-name
  "Build stack name and assoc in context.
   Stack name is taken in order from:

   - context
   - cli option"
  [context template-dir]
  (let [stack (get-in context [:docker :stack])
        stack (when-not stack
                (fs/file-name template-dir))]
    (when (str/blank? stack)
      (throw (ex-info "Stack should not be nil" {})))
    (assoc-in context [:docker :stack] stack)))

(defn kwd-key
  "Convert [\"a.c.d\" \"b\"] to [(:a :c :d) \"b\"].
   Splits key by dot (.) and converts to keywords."
  [kv]
  [(map keyword (str/split (first kv) #"\."))
   (second kv)])

(defn deep-merge
  "From https://clojuredocs.org/clojure.core/merge ."
  [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

(defn set-args->map
  "Convert vector of k.e.y=value entries to a map with all values merged."
  [set-args]
  (if-not (empty? set-args)
    (let [keys+vals (map #(str/split % #"=" 2) set-args)
          kwd-keys (map kwd-key keys+vals)
          maps (map #(assoc-in {} (first %) (second %)) kwd-keys)]
      (apply deep-merge maps))
    {}))

(defn final-context
  "Deep merge contexts, overwriting values."
  ([file-ctx cli-ctx & other]
   (log/debug "Merge" file-ctx cli-ctx)
   (let [template-dir (:template-dir file-ctx)
         file (str (fs/absolutize (fs/path template-dir "stack.generated.yml")))
         ctx (u/deep-merge file-ctx cli-ctx {:docker {:compose-file file}})
         ctx (apply u/deep-merge ctx other)]
       ;; TODO: some context validations
     ctx)))

(defn stack-name
  "Stack name from context."
  [ctx]
  (let [stack (get-in ctx [:docker :stack])]
    (assert stack "Stack should not be nil")
    stack))

(defn load-context!
  "Builds context as a map of values and returns it.
   Merges in values from cli to replace values from files.

   Return a map."
  [template-dir config secrets]
  (let [cwd (System/getProperty "user.dir")
        values (:values config)
        context (u/deep-merge config {:values values
                                      :secrets secrets
                                      :work-dir cwd
                                      :template-dir template-dir})]
    context))

(comment

  (load-context! "data/stacks/nginx" {} {})

  (u/deep-merge {} {:a "b"})
  (u/deep-merge {} {:a "b"} {:c "d"}))

