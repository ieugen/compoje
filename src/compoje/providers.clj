(ns compoje.providers
  "Providers help to integrate with other systems to extend compoje."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compoje.files :as files]
            [compoje.utils :as u]))

(def ^:private provider-types (atom {}))

(defprotocol SecretsProvider
  "Provider interface. Enhance compoje with other functionality."
  (provider-name [this]
    "Return the name of the provider. Can be a namespaced keyword.")
  (run [this context]
    "Provide compoje with secrets. Return a map with secret information."))

(defn register-provider!
  "Register a provider with a given key.
   Prefer to use namespaced clojure keywords like:
   - :compoje.providers/vault ."
  ([provider]
   (register-provider! (provider-name provider) provider))
  ([key provider]
   (log/debug "Register provider" key "->" provider)
   (swap! provider-types assoc key provider)))

(defn registered-providers
  "Return the names of registered providers."
  []
  (keys @provider-types))

(defn get-provider
  ([key]
   (let [providers @provider-types
         provider (get providers key)]
     (when-not (some? provider)
       (throw (ex-info (str "Provider does not exist: " key)
                       {:provider key})))
     provider)))

(defn provide-secrets
  "Call secrets providers"
  [config]
  (let [{:keys [template-dir providers secrets]} config
        by-name (set/index (into #{} providers) [:name])
        results (atom {})]
    (log/trace "We have providers" by-name)
    (doseq [secret secrets]
      (log/trace "Processing secret" secret)
      (let [{:keys [provider name]} secret
            provider-cfg (first (get by-name {:name provider}))
            type (some-> (:type provider-cfg) keyword)
            provider (get-provider type)
            result (run provider {:config provider-cfg
                                  :secret-spec secret
                                  :template-dir template-dir})]
        ;; keyword key: Selmer resolves template paths (<{ name.key }>) by keyword
        (swap! results assoc (keyword name) result)))
    @results))

(defn expand-home
  "Expand a leading ~ to `home`."
  [^String path home]
  (if (or (= "~" path) (str/starts-with? path "~/"))
    (str home (subs path 1))
    path))

(defn resolve-path
  "Absolute paths and ~ paths as given, others relative to `base-dir`."
  [base-dir path home]
  (let [p (fs/path (expand-home path home))]
    (str (if (fs/absolute? p) p (fs/path base-dir p)))))

^:rct/test
(comment
  (expand-home "~/x/pw" "/home/u") ;=> "/home/u/x/pw"
  (expand-home "~" "/home/u") ;=> "/home/u"
  (expand-home "~other/pw" "/home/u") ;=> "~other/pw"
  (expand-home "rel/pw" "/home/u") ;=> "rel/pw"
  (resolve-path "/stack" "pw.txt" "/home/u") ;=> "/stack/pw.txt"
  (resolve-path "/stack" "/etc/pw" "/home/u") ;=> "/etc/pw"
  (resolve-path "/stack" "~/pw" "/home/u") ;=> "/home/u/pw"
  )

(defn secret-content
  "The content to write for a secret: the whole content, or only `spit-key`
   (looked up as given, then as a keyword), rendered as edn/json if `format`
   asks for it. Pure."
  [{:keys [content spit-key format]}]
  (if (nil? spit-key)
    ;; pretty print only edn maps
    ;; https://github.com/amperity/vault-clj/issues/100
    (case format
      "edn" (u/pprint-str content)
      "json" (json/generate-string content)
      content)
    (if (contains? content spit-key)
      (get content spit-key)
      (get content (keyword spit-key)))))

^:rct/test
(comment
  (secret-content {:content "abc"}) ;=> "abc"
  (secret-content {:content {:a 1} :format "json"}) ;=> "{\"a\":1}"
  (secret-content {:content {"k" "v"} :spit-key "k"}) ;=> "v"
  (secret-content {:content {:k "v"} :spit-key "k"}) ;=> "v"
  (secret-content {:content {:k "v"} :spit-key "missing"}) ;=> nil
  )

(defn secret->file!
  "Write a secret spec's content to :local-path (relative to `base-dir`), if set.
   Writes the whole content or just :spit-key, see `secret-content`.
   Returns the secret's :content."
  ([secret-spec]
   (secret->file! (System/getProperty "user.dir") secret-spec))
  ([base-dir {:keys [local-path content] :as secret-spec}]
   (if local-path
     (let [path (-> (fs/file base-dir local-path) fs/absolutize fs/file)]
       (if (files/virtual?)
         ;; dry run: keep it in memory, file-hash and friends read it from there
         (do (log/info "Dry run: would write secret" (:name secret-spec) "to" (str path))
             (files/write! path (secret-content secret-spec)))
         (do (fs/create-dirs (fs/parent path))
             (log/trace "Write secret to" (str path))
             (spit path (secret-content secret-spec)))))
     (log/trace "Skip writing secret. Missing :local-path option."))
   content))

(comment

  (get-provider :aa)

  (let [providers [{:name :dre-vault
                    :type :compoje.providers.vault/vault
                    :addr "VAULT_ADDR"}]
        by-name (set/index (into #{} providers) [:name])]
    by-name))
