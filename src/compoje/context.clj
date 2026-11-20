(ns compoje.context
  "Functions to manage context"
  (:require [babashka.fs :as fs]
            [clojure.tools.logging :as log]
            [compoje.utils :as u]))

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
  ([file-ctx cli-ctx & other]
   (log/debug "Merge" file-ctx cli-ctx)
   (let [template-dir (:template-dir file-ctx)
         file (or (get-in file-ctx [:docker :compose-file])
                  (fs/path template-dir "stack.generated.yml"))
         file (str (fs/normalize (fs/absolutize file)))
         ctx (u/deep-merge file-ctx cli-ctx {:docker {:compose-file file}})
         ctx (apply u/deep-merge ctx other)]
       ;; TODO: some context validations
     ctx)))

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

(defn config->context
  "Builds the initial context from the x-compoje config and returns it.
   Keeps the whole config (docker, providers, secrets, values), so docker
   options and provider definitions reach deploy.
   This context does not contain data from providers.
   Return a map."
  [config & more]
  (let [cwd (System/getProperty "user.dir")]
    (apply u/deep-merge (assoc config :work-dir cwd) more)))

(defn enhanced-context
  "Enhance the context with provider data"
  [context provider-data]
  (u/deep-merge context provider-data))

(comment

  (config->context "data/stacks/nginx" {} {})

  (u/deep-merge {} {:a "b"})
  (u/deep-merge {} {:a "b"} {:c "d"}))

