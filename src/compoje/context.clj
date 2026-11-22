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
  "The render context: `ctx` (config, docker options, paths) with the
   provider results under :secrets, keyed by secret name (`secrets.<name>` in
   templates). Results live under their own key so a secret name can never
   overwrite another context key. The secret declarations (x-compoje secrets)
   are replaced by the results."
  [ctx provider-results]
  (log/trace "Final context" ctx)
  (assoc ctx :secrets (or provider-results {})))

^:rct/test
(comment
  (final-context {:values {:a 1} :secrets [{:name "app" :provider "v"}]} {:app {:pw "p"}})
  ;=> {:values {:a 1} :secrets {:app {:pw "p"}}}
  (final-context {:values {:a 1}} nil) ;=> {:values {:a 1} :secrets {}}
  )

(def redacted "<redacted>")

(defn redact-secrets
  "The context with every provider result (under :secrets) replaced by
   \"<redacted>\". For printing."
  [ctx]
  (cond-> ctx
    (map? (:secrets ctx)) (update :secrets update-vals (constantly redacted))))

^:rct/test
(comment
  (redact-secrets {:secrets {:app {:password "p"} :tok "t"} :values {:a 1}})
  ;=> {:secrets {:app "<redacted>" :tok "<redacted>"} :values {:a 1}}
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

