(ns compoje.cli
  "ns to offer a CLI interface for compoje features."
  (:require [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [compoje.logging :as clog]
            [taoensso.timbre :as log])

  (:gen-class))
(def main-opts
  [["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :update-fn inc]
   ["-h" "--help"]])


(defn usage [options-summary]
  (->> ["This is compoje program. Templates for docker swarm stacks."
        ""
        "Usage: compoje [options] action [action-specific-options]"
        ""
        "Options:"
        options-summary
        ""
        "Actions:"
        "  render     Render a stack template."
        "  deploy     Deploy a stack template to a swarm cluster."
        ""
        "Please refer to the manual page for more information."]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args main-opts :in-order true)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary) :ok? true}
      errors ; errors => exit with description of errors
      {:exit-message (error-msg errors)}
      ;; custom validation on arguments
      (and (= 1 (count arguments))
           (#{"render" "deploy"} (first arguments)))
      {:action (first arguments) :options options}
      :else ; failed custom validation => exit with usage summary
      {:exit-message (usage summary)})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn verbosity->log-level
  "Convert verbosity to log level."
  [verbosity]
  (case verbosity
    0 :warn
    1 :info
    2 :debug
    :trace))

(defn main
  "Default entry point for CLI app."
  [& args]
  (let [{:keys [action options exit-message ok?]} (validate-args args)
        {:keys [verbosity]} options]
    (log/merge-config! {:output-fn clog/output-fn
                        :min-level (verbosity->log-level verbosity)})
    (if exit-message
      (exit (if ok? 0 1) exit-message)
      (case action
        "render"
        (do
          (log/info "Rendering" options)
          (log/debug "Rendering" options)
          (log/trace "Rendering" options))
        "deploy"
        (log/info "Deploy" options)))))