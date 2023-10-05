(ns compoje.cli
  "ns to offer a CLI interface for compoje features."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [compoje.config :as config]
            [compoje.context :as context]
            [compoje.deploy.docker :as docker]
            [compoje.logging :as clog]
            [compoje.providers :as providers]
            [compoje.providers.vault :as vault-p]
            [compoje.render :as render]
            [compoje.utils :as u]
            [taoensso.timbre :as log]
            [babashka.fs :as fs])
  (:gen-class))

(def main-opts
  [["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc]
   ["d" "--deploy-driver" "Use a deployment driver. Defaults to :docker"
    :default :docker]
   ["-h" "--help"]])

(def deploy-opts
  "Deploy specific opts.
   Includes options to be passed down to docker client."
  [[nil "--stack STACK"
    "Specify the name of the stack. Ovewrite value from file."]
   [nil "--prune" "Prune services that are no longer referenced."]
   [nil "--resolve-image STRATEGY"
    "Query the registry to resolve image digest and supported platforms (always, changed, never)"
    :default "always"]
   [nil "--with-registry-auth"
    "Send registry authentication details to Swarm agents"
    :default true]
   [nil "--dry-run" "Dry run. Render stack. Do not deploy."
    :default false]
   ["-s" "--set VALUE" "Overwrite a context value using syntax: --set path.from.contex=value"
    :multi true
    :default []
    :update-fn conj]
   ;; --context is global option for docker
   [nil "--context CONTEXT"
    "Use this docker context. See https://docs.docker.com/engine/context/working-with-contexts/"]
   ["-h" "--help"]])

(def valid-actions #{"render" "deploy"})

(defn usage
  ([options-summary]
   (->> ["This is compoje program. Templates for docker swarm stacks."
         ""
         "Usage: compoje [options] action [action-specific-options]"
         ""
         "Options:"
         options-summary
         ""
         "Actions:"
        ;;  "  render     Render a stack template."
         "  deploy     Deploy a stack template to a swarm cluster."
         ""
         "Please refer to the manual page for more information."]
        (str/join \newline)))
  ([action global-options options-summary]
   (->> ["This is compoje program. Templates for docker swarm stacks."
         ""
         (str "Usage: compoje [options] " action " [" action "-options] path-to-stack")
         ""
         "Options:"
         global-options
         ""
         "Actions:"
        ;;  "  render     Render a stack template."
         "  deploy     Deploy a stack template to a swarm cluster."
         ""
         (str action " options")
         options-summary
         "Please refer to the manual page for more information."]
        (str/join \newline))))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn cli-opts->context
  "Build context from global options.
   Will be deep merged and overwrite values in "
  [options]
  (let [compoje (select-keys options [:deploy-driver])]
    {:compoje compoje}))

(defn validate-args
  "Validate command line arguments.
   Either return a map indicating the program should exit
   (with an error message, and optional ok status),
   or a map indicating the action the program should take
   and the options provided."
  [args]
  (let [parsed (cli/parse-opts args main-opts :in-order true)
        {:keys [options arguments errors summary]} parsed
        action (first arguments)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}

      errors
      {:exit-message (error-msg errors)}

      (valid-actions action)
      (assoc parsed
             :action action
             :context (cli-opts->context options))

      :else
      {:exit-message (usage summary)})))

(defn exit [status msg]
  (log/error msg)
  (System/exit status))

(defn verbosity->log-level
  "Convert verbosity to log level."
  [verbosity]
  (case verbosity
    0 :info
    1 :debug
    :trace))

(defn cli-deploy->context
  "Build partial context from cli opts for deploy command.
   This will be merged and overwrite context loaded from compoje."
  [options]
  (let [docker (select-keys options [:stack :prune :context
                                     :resove-image])]
    {:docker docker}))

(defn validate-deploy
  [args global-summary]
  (let [parsed (cli/parse-opts args deploy-opts :in-order true)
        {:keys [options arguments errors summary]} parsed
        config (first arguments)]
    (cond
      (:help options) ; help => exit OK with usage summary
      (exit 0 (usage "deploy" global-summary summary))

      errors
      (exit 1 (error-msg errors))

      (not= 1 (count arguments))
      (exit 1 (str "Deploy requires 1 argument: directory to deploy"))

      :else (assoc parsed
                   :config-file config))))
(defn parse-all-opts
  "Parse cli options in two stages:
   - global options
   - action specific options

   Return a map with all data with the followinf structure:
   TODO: add structure once stable."
  [args]
  (let [{:keys [action arguments options]
         :as global-parsed} (validate-args args)
        arguments (into [] (rest arguments))
        global-parsed (assoc global-parsed :arguments arguments)
        cmd-args (:arguments global-parsed)
        global-summary (:summary global-parsed)
        parsed-cmd (case action
                     "deploy" (validate-deploy cmd-args global-summary)
                     ;; else action-opts are nil
                     nil)
        global-ctx (cli-opts->context options)
        action-ctx (cli-deploy->context (:options parsed-cmd))]
    {:action action
     :global-opts global-parsed
     :action-opts parsed-cmd
     :context (u/deep-merge global-ctx action-ctx)}))

(defn register-default-providers!
  "Register providers available for calling."
  []
  (providers/register-provider! (vault-p/->VaultSecretsProvider)))

(defn- set-hooman-logging!
  "Make log format friendly to hoomans."
  [verbosity]
  (log/merge-config! {:output-fn clog/output-fn
                      :min-level (verbosity->log-level verbosity)}))

(defn deploy
  [parsed context]
  (let [{:keys [config-file options arguments]} parsed
        {dry-run :dry-run set-args :set} options
        config-file (config/absolute-path config-file)
        template-dir (config/config-name->template-dir config-file)
        config (config/load-config! config-file)
        provider-results (providers/provide-secrets
                          (assoc config :template-dir template-dir))
        file-ctx (context/load-context! template-dir config provider-results)
        cli-ctx (context/set-args->map set-args)
        context (context/final-context file-ctx context cli-ctx)
        docker (:docker context)
        template-file (str (fs/file template-dir "stack.tpl.yml"))
        contents (render/render template-file context {})]
    (log/debug "Deploy" config-file "opts" options
               "arguments" arguments)
    (log/debug "Deploy context" context)
    (if dry-run
      (do
        (log/debug "Skip deployment. Render only.")
        (log/info (u/pprint-str context) "\n\n")
        (log/info contents))
      (do
        (spit (:compose-file docker) contents)
        (docker/deploy docker options)))))

(defn main
  "Default entry point for CLI app."
  [& args]
  (let [parsed (parse-all-opts args)
        {:keys [action global-opts action-opts context]} parsed
        {:keys [options exit-message ok?]} global-opts
        {:keys [verbosity]} options]
    (set-hooman-logging! verbosity)
    (log/trace "Parsed command line is" parsed)
    (register-default-providers!)
    (log/debug "Providers are" (providers/registered-providers))
    ;; execute commands
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "deploy"
        (deploy action-opts context)))))
