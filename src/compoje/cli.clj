(ns compoje.cli
  "ns to offer a CLI interface for compoje features."
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [compoje.logging :as clog]
            [compoje.render :as core]
            [compoje.context :as context]
            [compoje.providers :as providers :refer [provider-name run]]
            [compoje.providers.vault :as vault-p]
            [compoje.deploy.docker :as docker]
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
  [[nil "--prune" "Prune services that are no longer referenced"]
   [nil "--resolve-image STRATEGY"
    "Query the registry to resolve image digest and supported platforms (always, changed, never)"
    :default "always"]
   ["-r" "--render-only" "Render stack as file, do not deploy."
    :default false]
   ;; --context is global option for docker
   [nil "--context CONTEXT" "Use this docker context.
                         See https://docs.docker.com/engine/context/working-with-contexts/"]
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
         (str "Usage: compoje [options] " action " [" action "-options]")
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

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
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
             :action action)

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

(defn render
  [args]
  (log/info "Render" args))

(defn validate-deploy
  [args global-summary]
  (let [parsed (cli/parse-opts args deploy-opts :in-order true)
        {:keys [options arguments errors summary]} parsed
        dir (first arguments)
        stack (second arguments)]
    (cond
      (:help options) ; help => exit OK with usage summary
      (exit 0 (usage "deploy" global-summary summary))

      errors
      (exit 1 (error-msg errors))

      (not= 2 (count arguments))
      (exit 1 (str "Deploy requires 2 arguments: directory and stack"))

      :else
      (assoc parsed
             :template-dir dir
             :stack stack))))

(defn deploy
  [global-parsed]
  (let [args (:arguments global-parsed)
        global-summary (:summary global-parsed)
        parsed (validate-deploy args global-summary)
        {:keys [template-dir stack options arguments]} parsed
        context (context/load-context! template-dir)
        file (str (fs/absolutize (fs/path template-dir "stack.generated.yml")))]
    (log/debug "Deploy" template-dir "as" stack
               "opts" options
               "arguments" arguments)
    (core/render->file template-dir context file {})
    (docker/deploy file stack {})))

(defn register-default-providers!
  []
  (let [vault (vault-p/->VaultSecretsProvider)]
    (providers/register-provider! (provider-name vault) vault)))

(defn main
  "Default entry point for CLI app."
  [& args]
  (let [{:keys [action options exit-message ok? arguments]
         :as parsed} (validate-args args)
        {:keys [verbosity]} options
        arguments (into [] (rest arguments))
        parsed (assoc parsed :arguments arguments)]
    ;; Make logging cli friendly
    (log/merge-config! {:output-fn clog/output-fn
                        :min-level (verbosity->log-level verbosity)})
    (register-default-providers!)
    (log/debug "Providers are" (providers/registered-providers))
    ;; execute commands
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        ;; "render"
        ;; (render parsed)
        "deploy"
        (deploy parsed)))))
