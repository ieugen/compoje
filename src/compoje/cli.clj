(ns compoje.cli
  "Command line interface for compoje, built on lambdaisland/cli."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compoje.config :as config]
            [compoje.context :as context]
            [compoje.deploy.docker :as docker]
            [compoje.logging :as clog]
            [compoje.migrate :as migrate]
            [compoje.providers :as providers]
            [compoje.providers.ansible-vault :as ansible-vault-p]
            [compoje.providers.file :as file-p]
            [compoje.providers.vault :as vault-p]
            [compoje.render :as render]
            [compoje.utils :as u]
            [lambdaisland.cli :as cli]
            [lambdaisland.cli.cmdspec :as cmdspec]
            [taoensso.timbre :as log])
  (:gen-class))

(defn verbosity->log-level
  "Convert verbosity to log level."
  [verbosity]
  (case (or verbosity 0)
    0 :info
    1 :debug
    :trace))

(defn- set-hooman-logging!
  "Make log format friendly to hoomans. Logs go to stderr, so stdout carries
   only data (the rendered stack), e.g. for `compoje render x.yml > out.yml`."
  [verbosity]
  (log/merge-config! {:output-fn clog/output-fn
                      :appenders {:println (log/println-appender {:stream :std-err})}
                      :min-level (verbosity->log-level verbosity)}))

(defn register-default-providers!
  "Register providers available for calling."
  []
  (providers/register-provider! (vault-p/->VaultSecretsProvider))
  (providers/register-provider! (ansible-vault-p/->AnsibleVaultSecretsProvider))
  (providers/register-provider! (file-p/->FileSecretsProvider)))

(def docker-defaults
  "Defaults for docker deploy options.
   x-compoje docker config and docker CLI flags override these."
  {:resolve-image "always"
   :with-registry-auth true})

(def docker-cli-keys
  "Deploy flags that map 1:1 onto keys under :docker in the context."
  [:stack :context :prune :resolve-image :with-registry-auth])

(defn cli-opts->docker
  "Docker options given explicitly on the command line.
   Flags that were not passed are left out, so they don't override
   the x-compoje config. `false` (e.g. --no-with-registry-auth) is kept."
  [opts]
  (into {}
        (filter (comp some? val))
        (select-keys opts docker-cli-keys)))

(defn cli-config->context
  "Build the context for a stack file.

   Precedence, lowest first:
   docker defaults < x-compoje config in the stack file < --set overrides < docker CLI flags."
  ([config-file set-args]
   (cli-config->context config-file set-args {}))
  ([config-file set-args cli-docker]
   (let [config-file (fs/file config-file)
         template-dir (config/config-name->template-dir config-file)
         config (config/load-config! config-file set-args)
         config (merge-with merge
                            {:docker docker-defaults}
                            config
                            {:docker cli-docker})]
     (when (get-in config [:docker :compose-file])
       (log/warn "x-compoje docker.compose-file is ignored: compoje no longer writes the stack to disk."
                 "Use `compoje render -o FILE` to save it."))
     (context/config->context (update config :docker dissoc :compose-file)
                              {:config-file config-file
                               :template-dir template-dir}))))

(defn deploy-context
  "Build the final context for a deploy: stack file config, CLI options
   and provider results."
  [{:keys [stack-file set] :as opts}]
  (let [ctx (cli-config->context stack-file set (cli-opts->docker opts))
        provider-results (providers/provide-secrets ctx)]
    (context/final-context ctx provider-results)))

(defn- render-stack
  "Build the context (providers included) and render the stack template.
   Returns [context rendered-stack]."
  [{:keys [verbosity] :as opts}]
  (set-hooman-logging! verbosity)
  (register-default-providers!)
  (log/debug "Providers are" (providers/registered-providers))
  (let [context (deploy-context opts)
        template-file (str (fs/file (:template-dir context) "stack.tpl.yml"))]
    (log/trace "Context" context)
    [context (render/render template-file context {})]))

(defn- printable-context [context show-secrets]
  (u/pprint-str (cond-> context (not show-secrets) context/redact-secrets)))

(def ^:private shared-flags
  ["--stack STACK" "Stack name. Overrides x-compoje docker.stack."
   "-s, --set <override>" {:doc "Override a config value: --set path.in.config=value. Repeatable."
                           :coll? true}])

(defn deploy
  "Deploy a stack template to a Docker Swarm cluster.

  Reads the x-compoje config from the stack file, applies --set overrides and
  docker flags, fetches secrets from providers, renders the template and runs
  docker stack deploy with the stack on stdin. The rendered stack is not
  written to disk; use `compoje render -o FILE` for that."
  {:flags (into shared-flags
                ["--context CONTEXT" "Docker context to deploy to (docker --context)."
                 "--prune" {:doc "Prune services that are no longer referenced."
                            :value true}
                 "--resolve-image STRATEGY" "Query the registry to resolve image digest and supported platforms: always, changed, never. Default: always."
                 "--[no-]with-registry-auth" "Send registry authentication details to Swarm agents. Default: on."
                 "--dry-run" {:doc "Print the docker command, the context (secrets redacted) and the rendered stack (stdout). Do not deploy."
                              :value true}
                 "--show-secrets" {:doc "With --dry-run: show provider results in the printed context."
                                   :value true}])}
  [{:keys [dry-run show-secrets deploy-driver] :as opts}]
  (when-not (= :docker deploy-driver)
    (throw (ex-info (str "Unsupported deploy driver: " (name deploy-driver)
                         ". Supported: docker.")
                    {:deploy-driver deploy-driver})))
  (let [[context contents] (render-stack opts)
        {:keys [template-dir docker]} context]
    (when (str/blank? (some-> (:stack docker) str))
      (throw (ex-info "No stack name. Set x-compoje docker.stack in the stack file, or pass --stack."
                      {:stack-file (:stack-file opts)})))
    (if dry-run
      (do
        (log/info (docker/deploy-str docker))
        (log/info (printable-context context show-secrets))
        (print contents)
        (flush))
      (docker/deploy docker contents template-dir)))
  ::done)

(defn render
  "Render a stack template and print it.

  Runs the secret providers and renders the template like deploy, then writes
  the result to stdout (logs go to stderr), or to a file with --output. The
  output can contain secret values: don't commit it."
  {:flags (into shared-flags
                ["-o, --output FILE" "Write the rendered stack to FILE instead of stdout (- means stdout)."])}
  [{:keys [output] :as opts}]
  (let [[_context contents] (render-stack opts)]
    (if (or (nil? output) (= "-" output))
      (do (print contents)
          (flush))
      (do (spit output contents)
          (log/warn "Wrote" output "- it can contain secret values; don't commit it."))))
  ::done)

(defn migrate
  "Migrate a stack directory from compoje.edn to x-compoje.

  Reads <dir>/compoje.edn and adds its config to <dir>/stack.tpl.yml as an
  x-compoje: section. compoje.edn is left in place: review the result, then
  delete it. Fails if compoje.edn uses aero tags (#env, #or, ...), or if the
  template already has an x-compoje section."
  {:flags ["--dry-run" {:doc "Print the migrated stack.tpl.yml. Do not write it."
                        :value true}]}
  [{:keys [dir dry-run verbosity]}]
  (set-hooman-logging! verbosity)
  (let [result (migrate/migrate! dir {:dry-run dry-run})]
    (if dry-run
      (print result)
      (log/info "Migrated" result "- review it, then delete"
                (str (fs/file dir "compoje.edn")))))
  ::done)

(defn version
  "compoje version, from resources/compoje/VERSION (\"unknown\" if missing)."
  []
  (or (some-> (io/resource "compoje/VERSION") slurp str/trim not-empty)
      "unknown"))

(def cmdspec
  {:name "compoje"
   :doc "Templates for docker swarm stacks."
   :strict? true
   :flags ["-v, --verbose" {:doc "Increase verbosity: -v debug, -vv trace."
                            :key :verbosity
                            :default 0}
           "--deploy-driver DRIVER" {:doc "Deployment driver. Only docker is supported."
                                     :default "docker"
                                     :parse keyword}
           "--version" "Print the compoje version and exit."]
   :commands ["deploy <stack-file>" #'deploy
              "render <stack-file>" #'render
              "migrate <dir>" #'migrate]})

(defn- help-requested? [args]
  (some #{"-h" "--help" "help"} args))

(defn- completion-command? [args]
  (some #(str/starts-with? % "__licli") args))

(defn main
  "Default entry point for the CLI app (used by the bb task).

  --version prints the version. Otherwise commands return ::done.
  lambdaisland/cli prints help and returns nil for usage errors (no command,
  unknown command, missing <stack-file>); exit 1 in that case so scripts can
  detect it. Parse errors and exceptions are reported by lambdaisland/cli
  itself, which exits 1."
  [& args]
  (if (some #{"--version"} args)
    (do (println "compoje" (version))
        ::done)
    (let [result (cli/dispatch (cmdspec/to-cmdspec cmdspec) (vec args))]
      (when-not (or (= ::done result)
                    (help-requested? args)
                    (completion-command? args))
        (System/exit 1))
      result)))

(defn -main
  "JVM entry point: clojure -M -m compoje.cli ..."
  [& args]
  (apply main args))
