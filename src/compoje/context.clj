(ns compoje.context
  "Functions to manage context"
  (:require [compoje.config :as config]
            [clojure.tools.logging :as log]))

(defn deep-merge
  "From https://clojuredocs.org/clojure.core/merge ."
  [a & maps]
  (if (map? a)
    (apply merge-with deep-merge a maps)
    (apply merge-with deep-merge maps)))

(defn load-values
  "Load values to use in template file."
  [dir]
  (log/debug "Load values from" (str dir))
  #_(apply deep-merge (map keywordize-keys (map cc/fetch-resource values)))
  {})

(defn load-context!
  "Builds context as a map of values and returns it.
   Merges in values from cli to replace values from files.

   Return a map."
  [template-dir]
  (let [config-path (config/config-path template-dir)
        config (config/load-config! config-path)
        cwd (System/getProperty "user.dir")]
    {:config config
     :values (load-values template-dir)
     :cwd cwd
     :template-dir template-dir
     :output "stack.generated.yml"}))


(comment

  (load-context! "data/stacks/nginx")

  )
