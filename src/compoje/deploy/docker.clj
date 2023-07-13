(ns compoje.deploy.docker
  "Deploy driver using docker native client"
  (:require [babashka.process :refer [shell]]
            [taoensso.timbre :as log]))

(defn deploy-str
  "Deploy stack"
  [template stack & opts]
  (let [{:keys [context resolve-image]} opts
        cli (str "docker "
                 (when context
                   (str "--context " context))
                 " stack deploy "
                 (when resolve-image
                   (str "--resolve-image " resolve-image))
                 " --compose-file " template " " stack)]
    cli))

(defn deploy
  [template stack & opts]
  (let [cli (deploy-str template stack opts)]
    (log/debug "Deploy using: " cli)
    (shell cli)))

(comment

  (deploy-str "data/stack/nginx/stack.generated.yml" "bbb"
              {:context "dre-main"})

  (deploy "data/stack/nginx/stack.generated.yml" "bbb"
          {:context "dre-main"})

  )