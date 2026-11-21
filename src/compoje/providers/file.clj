(ns compoje.providers.file
  "compoje provider for plain (unencrypted) data files: shared values, or
   secrets that are already on disk (CI secret files, mounted secrets).
   For encrypted files use compoje.providers.ansible-vault.

   Provider config (x-compoje providers):
     - name: files
       type: compoje.providers.file/file
       dir: shared               # optional base dir, relative to the stack dir, ~ allowed

   Secret spec (x-compoje secrets):
     - name: common              # result is available in templates as <{ common... }>
       provider: files
       file: common.yml          # relative to dir (or the stack dir); absolute and ~ as given
       format: yaml              # yaml, json, edn or raw; default from the file extension
       local-path: out/x.txt     # optional: write the file (or spit-key) to a file
       spit-key: password"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [compoje.providers :as providers]
            [yamlstar.core :as yaml]))

(def extension-formats
  "Default format per file extension (lower case). Other extensions are raw."
  {"yml" "yaml" "yaml" "yaml" "json" "json" "edn" "edn"})

(defn format-for
  "The format to parse `file` with: `format` when given, else from the extension."
  [file format]
  (or (some-> format name str/lower-case)
      (get extension-formats (str/lower-case (or (fs/extension file) "")))
      "raw"))

^:rct/test
(comment
  (format-for "a/b.yml" nil) ;=> "yaml"
  (format-for "b.YAML" nil) ;=> "yaml"
  (format-for "b.json" nil) ;=> "json"
  (format-for "b.edn" nil) ;=> "edn"
  (format-for "tls.key" nil) ;=> "raw"
  (format-for "noext" nil) ;=> "raw"
  (format-for "b.yml" "raw") ;=> "raw"
  (format-for "b.txt" "JSON") ;=> "json"
  )

(defn parse-content
  "Parse file text. yaml and json give maps with keyword keys, edn is read as
   is, raw returns the text."
  [text format]
  (case format
    "yaml" (walk/keywordize-keys (yaml/load text))
    "json" (json/parse-string text true)
    "edn" (edn/read-string text)
    "raw" text
    (throw (ex-info (str "Unknown file format: " format ". Use yaml, json, edn or raw.")
                    {:format format}))))

^:rct/test
(comment
  (parse-content "db:\n  host: h\n  port: 5432\n" "yaml") ;=> {:db {:host "h" :port 5432}}
  (parse-content "{\"db\": {\"host\": \"h\"}}" "json") ;=> {:db {:host "h"}}
  (parse-content "{:db {:host \"h\"}}" "edn") ;=> {:db {:host "h"}}
  (parse-content "s3cret\n" "raw") ;=> "s3cret\n"
  (try (parse-content "a" "toml") (catch Exception e (ex-message e)))
  ;=> "Unknown file format: toml. Use yaml, json, edn or raw."
  )

(defn secret-file
  "Absolute path of the secret's file: relative to the provider `dir`, which is
   itself relative to `template-dir`. Absolute and ~ paths are used as given."
  ([config secret-spec template-dir]
   (secret-file config secret-spec template-dir (System/getProperty "user.home")))
  ([{:keys [dir]} {:keys [file name]} template-dir home]
   (when-not file
     (throw (ex-info (str "file provider secret " name ": missing file") {:secret name})))
   (let [base (if dir (providers/resolve-path template-dir dir home) template-dir)]
     (str (fs/absolutize (providers/resolve-path base file home))))))

^:rct/test
(comment
  (secret-file {} {:file "a.yml"} "/stack" "/home/u") ;=> "/stack/a.yml"
  (secret-file {:dir "shared"} {:file "a.yml"} "/stack" "/home/u") ;=> "/stack/shared/a.yml"
  (secret-file {:dir "~/s"} {:file "a.yml"} "/stack" "/home/u") ;=> "/home/u/s/a.yml"
  (secret-file {:dir "shared"} {:file "/run/secrets/x"} "/stack" "/home/u") ;=> "/run/secrets/x"
  (try (secret-file {} {:name "n"} "/stack" "/home/u") (catch Exception e (ex-message e)))
  ;=> "file provider secret n: missing file"
  )

(defn load-secret
  "Read and parse the secret's file. Returns {:path :text :content}."
  [config {:keys [name format] :as secret-spec} template-dir]
  (let [path (secret-file config secret-spec template-dir)]
    (when-not (fs/regular-file? path)
      (throw (ex-info (str "file provider secret " name ": file not found: " path)
                      {:secret name :path path})))
    (log/debug "Load file secret" name "from" path)
    (let [text (slurp path)
          fmt (format-for path format)
          content (try (parse-content text fmt)
                       (catch Exception e
                         (throw (ex-info (str "file provider secret " name ": cannot read " path
                                              " as " fmt ": " (ex-message e))
                                         {:secret name :path path :format fmt}
                                         e))))]
      {:path path :text text :content content})))

(defrecord FileSecretsProvider []
  providers/SecretsProvider
  (run [_this {:keys [config secret-spec template-dir]}]
    (let [{:keys [text content]} (load-secret config secret-spec template-dir)
          ;; whole file to local-path: copy the original text, don't re-serialize
          to-write (if (contains? secret-spec :spit-key) content text)]
      ;; format raw: secret-content writes the value as is
      (providers/secret->file! template-dir (assoc secret-spec :content to-write :format "raw"))
      content))
  (provider-name [_this] ::file))
