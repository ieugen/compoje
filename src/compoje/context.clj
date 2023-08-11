(ns compoje.context
  "Functions to manage context"
  (:require [babashka.fs :as fs]
            [compoje.utils :as u]
            [clojure.tools.logging :as log]))

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

(defn final-context
  "Deep merge contexts, overwriting values."
  [file-ctx cli-ctx]
  (log/debug "Merge" file-ctx cli-ctx)
  (let [template-dir (:template-dir file-ctx)
        file (str (fs/absolutize (fs/path template-dir "stack.generated.yml")))
        ctx (u/deep-merge file-ctx cli-ctx {:docker {:compose-file file}})]
    ;; TODO: some context validations
    ctx))

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

