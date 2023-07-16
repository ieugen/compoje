(ns compoje.config
  (:require [aero.core :refer [read-config]]
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
   Returns a clojure data structure."
  [path]
  (read-config path))

(comment

  (read-config "data/stacks/nginx/compoje.edn")

  )