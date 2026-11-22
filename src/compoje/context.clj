(ns compoje.context
  "Functions to manage context"
  (:require [clojure.tools.logging :as log]
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
   (log/trace "Merge" file-ctx cli-ctx)
   ;; TODO: some context validations
   (apply u/deep-merge file-ctx cli-ctx other)))

(def redacted "<redacted>")

(defn redact-secrets
  "The context with every provider result (top-level keys named after the
   declared secrets) replaced by \"<redacted>\". For printing."
  [ctx]
  (reduce (fn [c n]
            (let [k (keyword (str n))]
              (cond-> c (contains? c k) (assoc k redacted))))
          ctx
          (keep :name (:secrets ctx))))

^:rct/test
(comment
  (redact-secrets {:secrets [{:name "app"} {:name "missing"}]
                   :app {:password "p"}
                   :values {:a 1}})
  ;=> {:secrets [{:name "app"} {:name "missing"}] :app "<redacted>" :values {:a 1}}
  (redact-secrets {:values {:a 1}}) ;=> {:values {:a 1}}
  )

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

(comment

  (config->context {:values {:a 1}} {:template-dir "example-stacks/nginx"})

  (u/deep-merge {} {:a "b"})
  (u/deep-merge {} {:a "b"} {:c "d"}))

