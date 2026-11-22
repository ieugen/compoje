(ns compoje.cli
  "Command line interface for compoje, built on lambdaisland/cli."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [compoje.completion :as completion]
            [compoje.config :as config]
            [compoje.context :as context]
            [compoje.deploy.api :as api]
            [compoje.deploy.docker :as docker]
            [compoje.deploy.swarm-convert :as swarm-convert]
            [compoje.docker.endpoint :as endpoint]
            [compoje.init :as init]
            [compoje.logging :as clog]
            [compoje.migrate :as migrate]
            [compoje.providers :as providers]
            [compoje.providers.ansible-vault :as ansible-vault-p]
            [compoje.providers.file :as file-p]
            [compoje.providers.vault :as vault-p]
            [compoje.render :as render]
            [compoje.schema :as schema]
            [compoje.stack :as stack]
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

(defn validated-config
  "Validate the x-compoje config (after --set) of `config-file`: log warnings,
   throw on errors listing them all. Returns the config with --set strings
   coerced where the schema wants booleans."
  [config-file config]
  (let [{:keys [config errors warnings]} (schema/validate config)]
    (doseq [w warnings] (log/warn (str config-file ": " w)))
    (when (seq errors)
      (throw (ex-info (str "Invalid x-compoje configuration in " config-file ":\n  "
                           (str/join "\n  " errors)
                           "\nSee docs/configuration.md.")
                      {:file (str config-file)})))
    config))

(defn cli-config->context
  "Build the context for a stack file.

   Precedence, lowest first:
   docker defaults < x-compoje config in the stack file < --set overrides < docker CLI flags."
  ([config-file set-args]
   (cli-config->context config-file set-args {}))
  ([config-file set-args cli-docker]
   (let [config-file (fs/file config-file)
         template-dir (config/config-name->template-dir config-file)
         config (validated-config config-file (config/load-config! config-file set-args))
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

(defn check-variables
  "Check the variables of template `text` against `context` before rendering.
   Undefined variables render as empty strings, so: a `secrets.` path or a
   top-level reference to a declared secret is an error (an empty secret is
   never intended); any other undefined variable is a warning. Returns the
   warnings; throws with all errors."
  [template-file text context]
  (let [secret-names (set (map name (keys (:secrets context))))
        undefined (render/undefined-variables text context)
        error? #(or (str/starts-with? % "secrets.") (secret-names (first (str/split % #"\."))))
        errors (for [v undefined :when (error? v)]
                 (if (str/starts-with? v "secrets.")
                   (str v ": no such provider result (secrets: "
                        (if (seq secret-names) (str/join ", " (sort secret-names)) "none declared") ")")
                   (str v ": provider results are under secrets; write secrets." v
                        " (compoje migrate rewrites old references)")))]
    (when (seq errors)
      (throw (ex-info (str "Undefined template variables in " template-file
                           " (they would render empty):\n  " (str/join "\n  " errors))
                      {:file (str template-file)})))
    (vec (for [v undefined :when (not (error? v))]
           (str template-file ": template variable " v " is not defined; it renders empty"
                " (add |default:\"...\" if that is intended)")))))

(defn- render-stack
  "Build the context (providers included) and render the stack template.
   Returns [context rendered-stack]."
  [{:keys [verbosity] :as opts}]
  (set-hooman-logging! verbosity)
  (register-default-providers!)
  (log/debug "Providers are" (providers/registered-providers))
  (let [context (deploy-context opts)
        ;; the stack file is the template: render the file that was passed
        template-file (str (:config-file context))]
    (log/trace "Context" context)
    (doseq [w (check-variables template-file (render/load-template template-file) context)]
      (log/warn w))
    [context (render/render template-file context {})]))

(defn prepare-stack
  "Split the rendered stack into what docker gets and the managed objects
   (x-compoje secrets/configs) compoje creates itself. Returns
   [stack-for-docker objects parsed]: stacks without managed objects pass
   through as text (parsed nil); others are parsed (YAML 1.1 octal ints, like
   docker), externalized and sent as JSON (valid YAML)."
  [{:keys [template-dir docker] :as context} contents]
  (if-not (stack/uses-managed-objects? contents)
    [contents [] nil]
    (let [parsed (swarm-convert/parse-stack contents)
          objects (stack/managed-objects
                   parsed
                   {:context context
                    :stack-name (:stack docker)
                    :render-template (fn [path]
                                       (let [f (str (fs/file template-dir path))]
                                         (doseq [w (check-variables f (render/load-template f) context)]
                                           (log/warn w))
                                         (render/render f context {})))})
          externalized (stack/externalize parsed objects)]
      [(json/generate-string externalized) objects externalized])))

(defn deploy-driver
  "Engine to deploy with: --deploy-driver, else x-compoje docker.driver, else
   docker-cli. `docker` is an alias of docker-cli."
  [flag context]
  (let [d (some-> (or flag (get-in context [:docker :driver]) "docker-cli") name)]
    (case d
      ("docker" "docker-cli") :docker-cli
      "docker-api" :docker-api
      (throw (ex-info (str "Unsupported deploy driver: " d ". Supported: docker-cli (alias docker), docker-api.")
                      {:deploy-driver d})))))

^:rct/test
(comment
  (deploy-driver nil {}) ;=> :docker-cli
  (deploy-driver :docker {:docker {:driver "docker-api"}}) ;=> :docker-cli
  (deploy-driver nil {:docker {:driver "docker-api"}}) ;=> :docker-api
  (try (deploy-driver "k8s" {}) (catch Exception e (ex-message e)))
  ;=> "Unsupported deploy driver: k8s. Supported: docker-cli (alias docker), docker-api."
  )

(defn api-endpoint
  "Docker endpoint for the docker-api engine: docker.context (or the usual
   resolution) plus x-compoje docker.ssh-mode and docker.allow-insecure-tcp."
  [{:keys [docker]}]
  (cond-> (endpoint/resolve-endpoint {:context (:context docker)})
    (:ssh-mode docker) (assoc :ssh-mode (keyword (name (:ssh-mode docker))))
    (:allow-insecure-tcp docker) (assoc :allow-insecure-tcp true)))

(defn- docker-client-config
  "The docker client config.json ($DOCKER_CONFIG or ~/.docker), or {}."
  []
  (let [f (fs/path (endpoint/docker-config-dir (into {} (System/getenv)) (System/getProperty "user.home"))
                   "config.json")]
    (if (fs/exists? f) (json/parse-string (slurp (str f))) {})))

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
  [{:keys [dry-run show-secrets] :as opts}]
  (let [[context contents] (render-stack opts)
        {:keys [template-dir docker]} context
        driver (deploy-driver (:deploy-driver opts) context)]
    (when (str/blank? (some-> (:stack docker) str))
      (throw (ex-info "No stack name. Set x-compoje docker.stack in the stack file, or pass --stack."
                      {:stack-file (:stack-file opts)})))
    (let [[stack-text objects parsed] (prepare-stack context contents)]
      (when dry-run
        (log/info (printable-context context show-secrets))
        (print contents)
        (flush))
      (case driver
        :docker-cli
        (if dry-run
          (do (docker/create-objects! docker objects true)
              (log/info (docker/deploy-str docker)))
          (do (docker/create-objects! docker objects false)
              (docker/deploy docker stack-text template-dir)))
        :docker-api
        (let [ep (api-endpoint context)]
          (log/info "Deploy using the Docker API at" (:host-url ep) (when dry-run "(dry run)"))
          (api/deploy! (or parsed (swarm-convert/parse-stack contents))
                       {:endpoint ep
                        :docker docker
                        :objects objects
                        :template-dir template-dir
                        :docker-config (docker-client-config)
                        :dry-run dry-run})))))
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

(defn- migrate-report
  "Human-readable lines about a migrate! result."
  [{:keys [file changed edn secret-refs template-files converted skipped plaintext-files]} dry-run]
  (concat
   (when edn ["compoje.edn: config moved into the x-compoje section."])
   (when (pos? (or secret-refs 0))
     [(str "Template: " secret-refs " reference(s) to provider results now use secrets.<name>.")])
   (for [{:keys [file refs]} template-files]
     (str file ": " refs " reference(s) to provider results now use secrets.<name>."))
   (for [{:keys [secret from defs dropped-keys]} converted]
     (str "Secret " secret ": " (str/join ", " (map #(str/join "." %) defs))
          " now created from memory (x-compoje from: " from "); local-path removed."
          (when (seq dropped-keys) (str " Dropped keys: " (str/join ", " dropped-keys) "."))))
   (for [{:keys [secret local-path reason]} skipped]
     (str "Skipped secret " secret " (local-path " local-path "): " reason))
   (for [f plaintext-files]
     (str "Plaintext secret file " f " is no longer needed: delete it after a successful deploy."))
   [(cond
      (not changed) "Nothing to migrate."
      dry-run (str "Dry run: " file " not written.")
      :else (str "Migrated " file ". Review it"
                 (when edn " and delete compoje.edn") "."))]))

(defn migrate
  "Migrate a stack directory to the current stack format.

  Two steps, each when it applies:
  - compoje.edn → x-compoje: adds compoje.edn's config to <dir>/stack.tpl.yml
    as an x-compoje: section (compoje.edn is left in place; delete it after
    reviewing). Fails if compoje.edn uses aero tags (#env, #or, ...).
  - local-path secrets → Docker secrets created from memory: definitions with
    `file: <local-path>` become `x-compoje: {from: ...}` and local-path /
    spit-key are removed, so secrets are no longer written to disk. Secrets
    that can't be converted safely are left as they are and reported."
  {:flags ["--dry-run" {:doc "Print the migrated stack.tpl.yml (stdout) and the report (stderr). Do not write."
                        :value true}]}
  [{:keys [dir dry-run verbosity]}]
  (set-hooman-logging! verbosity)
  (let [result (migrate/migrate! dir {:dry-run dry-run})]
    (when dry-run
      (print (:text result))
      (flush))
    (doseq [line (migrate-report result dry-run)]
      (log/info line)))
  ::done)

(defn init
  "Create a new stack directory.

  Writes <dir>/stack.tpl.yml (an x-compoje section, an example nginx service
  and a config rendered from html/index.html), html/index.html and a
  .gitignore. The stack name defaults to the directory name. An existing
  stack.tpl.yml is not overwritten unless --force; an existing .gitignore is
  always kept."
  {:flags ["--stack NAME" "Stack name (default: the directory name)."
           "--force" {:doc "Overwrite an existing stack.tpl.yml and html/index.html."
                      :value true}]}
  [{:keys [dir stack force verbosity]}]
  (set-hooman-logging! verbosity)
  (doseq [{:keys [path action]} (init/init! dir {:stack stack :force force})]
    (log/info (case action :created "Created" :overwritten "Overwrote" :kept "Kept existing") path))
  (log/info (str "\nNext: compoje deploy --dry-run " (fs/path dir "stack.tpl.yml")
                 "\n      (or bb compoje ... when compoje runs as a bb task)"))
  ::done)

(declare cmdspec)

(defn completion
  "Print a shell completion script: bash or zsh.

  The script completes commands, flags, flag values and stack files, for
  `compoje ...` and `bb compoje ...`. Install it once; see docs/install.md:
    compoje completion bash > ~/.local/share/bash-completion/completions/compoje"
  [{:keys [shell]}]
  (let [m (completion/model cmdspec)]
    (print (case shell
             "bash" (completion/bash-script m)
             "zsh" (completion/zsh-script m)
             (throw (ex-info (str "Unknown shell: " shell ". Supported: bash, zsh.") {:shell shell}))))
    (flush))
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
           "--deploy-driver DRIVER" {:doc "Deploy engine: docker-cli (default; alias docker) runs `docker stack deploy`, docker-api talks to the Docker API directly (no docker CLI needed). Overrides x-compoje docker.driver."
                                     :parse keyword}
           "--version" "Print the compoje version and exit."]
   :commands ["deploy <stack-file>" #'deploy
              "render <stack-file>" #'render
              "migrate <dir>" #'migrate
              "init <dir>" #'init
              "completion <shell>" #'completion]})

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
