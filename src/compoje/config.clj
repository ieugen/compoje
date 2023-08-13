(ns compoje.config
  (:require [aero.core :refer [read-config]]
            [clojure.tools.logging :as log]
            [babashka.fs :as fs]))

(defn absolute-path
  "Build a path to the compoje configuration inside the template directory."
  ([config-file]
   (-> (fs/file config-file)
       fs/absolutize
       fs/file)))

(defn config-name->template-dir
  "Takes a config file and returns the template directory."
  ([config-file]
   (let [f (-> (fs/file config-file)
               fs/parent
               str)]
     (log/trace "Template dir" f)
     f)))

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

  (read-config "data/stacks/nginx/compoje.edn"))