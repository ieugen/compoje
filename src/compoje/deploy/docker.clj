(ns compoje.deploy.docker
  "Deploy driver using docker native client"
  (:require [babashka.process :refer [shell]]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn- docker-base [{:keys [context]}]
  (cond-> ["docker"] context (into ["--context" (str context)])))

(defn deploy-args
  "Arguments for `docker stack deploy`, as a vector (no shell quoting needed).
   The stack is read from stdin (--compose-file -), so it is never written to disk."
  [docker]
  (let [{:keys [stack resolve-image with-registry-auth prune]} docker]
    (log/trace "deploy-args" docker)
    (cond-> (docker-base docker)
      true (into ["stack" "deploy"])
      with-registry-auth (conj "--with-registry-auth")
      resolve-image (into ["--resolve-image" (str resolve-image)])
      prune (conj "--prune")
      true (into ["--compose-file" "-" (str stack)]))))

(defn object-create-args
  "Arguments for `docker secret|config create` of a managed object; the data
   goes on stdin (the trailing -), never on disk or the command line."
  [docker {:keys [kind name labels]}]
  (-> (docker-base docker)
      (into [(clojure.core/name kind) "create"])
      (into (mapcat (fn [[k v]] ["--label" (str k "=" v)]) (sort labels)))
      (into [name "-"])))

(defn existing-names
  "Names of the existing secrets or configs (`kind` :secret / :config)."
  [docker kind]
  (let [args (into (docker-base docker) [(name kind) "ls" "--format" "{{.Name}}"])
        out (:out (apply shell {:out :string :err :string} args))]
    (set (remove str/blank? (str/split-lines out)))))

(defn create-objects!
  "Create the managed secrets/configs that don't exist yet. Objects are
   immutable, and the name holds a content hash, so an existing name means the
   same content. With dry-run only log what would happen."
  [docker objects dry-run]
  (doseq [[kind objs] (group-by :kind objects)]
    (let [existing (if dry-run #{} (existing-names docker kind))]
      (doseq [{:keys [name data] :as obj} objs]
        (cond
          (contains? existing name) (log/info "Using existing" (clojure.core/name kind) name)
          dry-run (log/info "Would create" (clojure.core/name kind) name
                            (str "(" (count (.getBytes ^String data "UTF-8")) " bytes)"))
          :else (do (log/info "Creating" (clojure.core/name kind) name)
                    (apply shell {:in data :out :string :err :string}
                           (object-create-args docker obj))))))))

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

  (object-create-args {:context "c"} {:kind :secret :name "pw-1" :labels {"a" "b"}})

  (deploy-str {:context "dre-main"
               :stack "bbb"}))
