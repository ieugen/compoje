(ns compoje.deploy.docker
  "Deploy driver using docker native client"
  (:require [babashka.process :refer [shell]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn deploy-args
  "Arguments for `docker stack deploy`, as a vector (no shell quoting needed).
   The stack is read from stdin (--compose-file -), so it is never written to disk."
  [docker]
  (let [{:keys [stack context resolve-image with-registry-auth prune]} docker]
    (log/trace "deploy-args" docker)
    (cond-> ["docker"]
      context (into ["--context" (str context)])
      true (into ["stack" "deploy"])
      with-registry-auth (conj "--with-registry-auth")
      resolve-image (into ["--resolve-image" (str resolve-image)])
      prune (conj "--prune")
      true (into ["--compose-file" "-" (str stack)]))))

(defn deploy-str
  "The deploy command as a string, for logging and dry runs."
  [docker]
  (str/join " " (deploy-args docker)))

(defn deploy
  "Run docker stack deploy with the rendered `stack` on stdin, in directory
   `dir` (the template dir, so relative file: paths resolve).
   Exits the process if docker fails."
  [docker stack dir]
  (let [args (deploy-args docker)]
    (log/info "Deploy using:" (str/join " " args))
    (try
      (apply shell {:in stack :dir (str dir) :err :string} args)
      (catch Exception e
        (let [{:keys [err cmd type]} (ex-data e)]
          (when (= type :babashka.process/error)
            (log/error "Exception deploying:" cmd)
            (log/error "Error:" err)))
        (System/exit 1)))))

(comment

  (deploy-str {:context "dre-main"
               :stack "bbb"}))
