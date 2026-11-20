(ns compoje.config
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [compoje.utils :as u]
            [yamlstar.core :as yaml])
  (:import [java.io IOException]))

(defn config-name->template-dir
  "Takes a config file and returns the template directory."
  ([config-file]
   (let [f (-> (fs/file config-file)
               fs/parent
               str)]
     (log/trace "Template dir" f)
     f)))

(defn read-config-yaml
  "Parse YAML from any source clojure.java.io/reader accepts:
   String path, bytes, char array, URL, File, InputStream.

   Parses YAML 1.2 with yamlstar (pure Clojure, runs on babashka).
   Map keys are keywordized, as the rest of compoje expects."
  [name]
  (-> (slurp name)
      yaml/load
      walk/keywordize-keys))

(defn file->config!
  "Read config as a yaml.
   config can be any source clojure.java.io/reader accepts:
   - String, URI, InputStream, File, etc.

   Return the data under the x-compoje key.
   The x- prefix makes it a compose extension field, so the rendered stack
   is still a valid compose file for docker stack deploy.
   A legacy top-level compoje key is still read, with a warning.

   If config is nil, return nil.
   On ^IOException print warning and return nil."
  [config]
  (when config
    (try
      (let [cfg (read-config-yaml config)]
        (if (contains? cfg :x-compoje)
          (get cfg :x-compoje)
          (when (contains? cfg :compoje)
            (u/println-err
             "WARN: top-level 'compoje:' key is deprecated, rename it to 'x-compoje:'."
             "docker stack deploy rejects unknown top-level keys.")
            (get cfg :compoje))))
      (catch IOException e
        (u/println-err
         "WARN: Error reading config" (.getMessage e))))))

(defn load-config!
  "Load configuration and merge options.

   Options are loaded in this order.
   Sbsequent values are deepmerged and replace previous ones.

   - configuration file - if it exists and we can parse it
   - command line arguments passed to the application

   Return a configuration map."
  [config-file set-args]
  (let [config (file->config! config-file)
        args (u/set-args->map set-args)]
    (u/deep-merge config args)))

(comment

  (def cfg (read-config-yaml "example-stacks/nginx/stack.tpl.yml"))
  cfg
  (def cfg (file->config! "example-stacks/nginx/stack.tpl.yml"))

  (type (:providers cfg))

  (str/lower-case (fs/extension "a.Yaml")))