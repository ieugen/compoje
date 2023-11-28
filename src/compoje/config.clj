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

(defn cli-args->config
  "Parse any configuration options from cli args.

   Return a configuration map with any values.

   We expect the args we receive to be values
   processed by tools.cli parse-opts fn."
  [config-edn-str]
  (let [config (edn/read-string config-edn-str)]
    (if (map? config)
      config
      {})))


(defn read-config-yaml
  "Parse yaml from a file path"
  [name]
  (->
   (yaml/parse-stream (io/reader name))
   unlazify))

(defn file->config!
  "Read config-file as a edn.
   If config-file is nil, return nil.
   On IO exception print warning and return nil."
  [^String config-file]
  (when config-file
    (try
      (let [config-path (fs/file config-file)
            cfg (read-config-yaml config-path)]
        (get cfg :compoje))
      (catch IOException e
        (u/println-err "WARN: Error reading config" (.getMessage e))))
    ))

(defn load-config!
  "Load configuration and merge options.

   Options are loaded in this order.
   Sbsequent values are deepmerged and replace previous ones.

   - configuration file - if it exists and we can parse it
   - command line arguments passed to the application

   Return a configuration map."
  [config-file config-data]
  (let [config (file->config! config-file)
        args (cli-args->config config-data)]
    (u/deep-merge config args)))

(comment


  (def cfg (read-config-yaml "example-stacks/nginx/stack.tpl.yml"))
  cfg
  (def cfg (file->config! "example-stacks/nginx/stack.tpl.yml"))

  (type (:providers cfg))

  (str/lower-case (fs/extension "a.Yaml"))



  )