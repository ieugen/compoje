(ns compoje.deploy.docker
  "Deploy driver using docker native client"
  (:require [babashka.process :refer [shell]]
            [taoensso.timbre :as log]))

(defn deploy-str
  "Deploy stack"
  ([template stack]
   (deploy-str template stack nil))
  ([template stack opts]
   (log/trace "deploy-str" template "->" stack
              "opts:" opts)
   (let [{:keys [context resolve-image]} opts
         cli (str "docker "
                  (when context
                    (str "--context " context))
                  " stack deploy "
                  (when resolve-image
                    (str "--resolve-image " resolve-image))
                  " --compose-file " template " " stack)]
     cli)))

(defn deploy
  "TODO: Move deployment computation up to participate in dry-run"
  ([template stack]
   (deploy template stack nil))
  ([template stack opts]
   (let [cli (deploy-str template stack opts)
         {:keys [dry-run]} opts]
     (log/info "Deploy using:" cli)
     (if dry-run
       (log/info "Dry-run:" cli)
       (shell cli)))))

(comment

  (deploy-str "data/stack/nginx/stack.generated.yml" "bbb"
              {:context "dre-main"})

  (deploy "data/stack/nginx/stack.generated.yml" "bbb"
          {:context "dre-main"}))