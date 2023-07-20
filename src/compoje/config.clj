(ns compoje.config
  (:require [aero.core :refer [read-config]]
            [clojure.tools.logging :as log]
            [babashka.fs :as fs]))

(def ^:dynamic compoje-config-name "compoje.edn")

(defn config-path
  "Build a path to the compoje configuration inside the template directory."
  ([template-dir]
   (-> (fs/file template-dir compoje-config-name)
       fs/absolutize
       fs/file)))

(defn load-config!
  "Read configuration from a path.
   Returns a clojure map."
  [path]
  (try
    (read-config path)
    (catch java.io.FileNotFoundException e
      (log/debug "File not found" (str path))
      (log/trace "Exception" e)
      ;; Use nil when configuration is missing
      nil)))

(comment

  (read-config "data/stacks/nginx/compoje.edn")

  )