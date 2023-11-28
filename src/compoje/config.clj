(ns compoje.config
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [clojure.string :as str]
            [compoje.utils :as u])
  (:import [java.io IOException]))

(defn unlazify
  "Make vectors instead of lazy sequences
   https://github.com/clj-commons/clj-yaml/issues/110"
  [x]
  (let [seq->vec #(if (= clojure.lang.LazySeq (type %))
                    (vec %)
                    %)]
    (walk/postwalk seq->vec x)))

(defn config-name->template-dir
  "Takes a config file and returns the template directory."
  ([config-file]
   (let [f (-> (fs/file config-file)
               fs/parent
               str)]
     (log/trace "Template dir" f)
     f)))

(defn read-config-yaml
  "Parse yaml using clojure.java.io/reader.
   Accepts any source reader accepts:
   String, bytes, URL, file, InputStream.

   Parses Yaml 1.1 format - packaged with babashka.
   TODO: find a way to use more cleaner Yaml 1.2"
  [name]
  (->
   (yaml/parse-stream (io/reader name))
   unlazify))

(defn file->config!
  "Read config as a yaml.
   config can be any source clojure.java.io/reader accepts:
   - String, URI, InputStream, File, etc.

   Return the data under compoje key.

   If config is nil, return nil.
   On ^IOException print warning and return nil."
  [config]
  (when config
    (try
      (let [cfg (read-config-yaml config)]
        (get cfg :compoje))
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

  (str/lower-case (fs/extension "a.Yaml"))



  )