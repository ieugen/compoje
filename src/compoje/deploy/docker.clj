(ns compoje.deploy.docker
  "Deploy driver using docker native client"
  (:require [babashka.process :refer [shell]]
            [taoensso.timbre :as log]))

(defn deploy-str
  "Deploy stack"
  ([docker]
   (let [{:keys [stack context resolve-image compose-file]} docker]
     (log/trace "deploy-str" docker ":" compose-file "->" stack)
     (let [cli (str "docker "
                    (when context
                      (str "--context " context))
                    " stack deploy "
                    (when resolve-image
                      (str "--resolve-image " resolve-image))
                    " --compose-file " compose-file " " stack)]
       cli))))

(defn deploy
  "TODO: Move deployment computation up to participate in dry-run"
  ([docker]
   (deploy docker nil))
  ([docker opts]
   (let [cli (deploy-str docker)
         {:keys [dry-run]} opts]
     (log/info "Deploy using:" cli)
     (if dry-run
       (log/info "Dry-run:" cli)
       (try
         (shell {:err :string} cli)
         (catch Exception e
           (let [{:keys [err cmd type]} (ex-data e)]
             (when (= type :babashka.process/error)
               (log/error "Exception deploying:" cmd)
               (log/error "Error:" err)))
           (System/exit -1)))))))

(comment

  (deploy-str {:context "dre-main"
               :stack "bbb"
               :compose-file "data/stack/nginx/stack.generated.yml"})

  (deploy {:context "dre-main"
           :stack "bbb"
           :compose-file "data/stack/nginx/stack.generated.yml"}))