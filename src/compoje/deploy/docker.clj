(ns compoje.deploy.docker
  "Deploy driver using docker native client"
  (:require [babashka.process :refer [shell]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn deploy-args
  "Arguments for `docker stack deploy`, as a vector (no shell quoting needed)."
  [docker]
  (let [{:keys [stack context resolve-image compose-file with-registry-auth prune]} docker]
    (log/trace "deploy-args" docker)
    (cond-> ["docker"]
      context (into ["--context" (str context)])
      true (into ["stack" "deploy"])
      with-registry-auth (conj "--with-registry-auth")
      resolve-image (into ["--resolve-image" (str resolve-image)])
      prune (conj "--prune")
      true (into ["--compose-file" (str compose-file) (str stack)]))))

(defn deploy-str
  "The deploy command as a string, for logging and dry runs."
  [docker]
  (str/join " " (deploy-args docker)))

(defn deploy
  "Run docker stack deploy. Exits the process if docker fails."
  [docker]
  (let [args (deploy-args docker)]
    (log/info "Deploy using:" (str/join " " args))
    (try
      (apply shell {:err :string} args)
      (catch Exception e
        (let [{:keys [err cmd type]} (ex-data e)]
          (when (= type :babashka.process/error)
            (log/error "Exception deploying:" cmd)
            (log/error "Error:" err)))
        (System/exit 1)))))

(comment

  (deploy-str {:context "dre-main"
               :stack "bbb"
               :compose-file "data/stack/nginx/stack.generated.yml"})

  (deploy {:context "dre-main"
           :stack "bbb"
           :compose-file "data/stack/nginx/stack.generated.yml"}))