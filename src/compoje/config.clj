(ns compoje.config
  (:require [aero.core :refer [read-config]]
            [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [clojure.string :as str]))

(defn unlazify
  "Make vectors instead of lazy sequences
   https://github.com/clj-commons/clj-yaml/issues/110"
  [x]
  (let [seq->vec #(if (= clojure.lang.LazySeq (type %))
                    (vec %)
                    %)]
    (walk/postwalk seq->vec x)))

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

(defn read-config-yaml
  "Parse yaml from a file path"
  [path]
  (->
   (yaml/parse-stream (io/reader path))
   unlazify))

(defn load-config!
  "Read configuration from a path.
   Returns a clojure map."
  [path]
  (let [ext (str/lower-case (fs/extension path))]
    (try
      (if (= ext "edn")
        (read-config path)
        ;; Consider yaml by default
        (read-config-yaml path))
      (catch java.io.FileNotFoundException e
        (log/debug "File not found" (str path))
        (log/trace "Exception" e)
      ;; Use nil when configuration is missing
        nil))))

(comment

  (read-config "example-stacks/nginx/compoje.edn")


  (def cfg (read-config-yaml "example-stacks/nginx/compoje.yml"))
  cfg

  (type (:providers cfg))

  (str/lower-case (fs/extension "a.Yaml") )


  )