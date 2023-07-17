(ns compoje.providers
  "Providers help to integrate with other systems to extend compoje."
  (:require [clojure.tools.logging :as log]
            [clojure.set :as set]))

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
  (^SecretsProvider [key]
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
        by-name (set/index (into #{} providers) [:name])]
    (log/info "We have providers" by-name)
    (doseq [secret secrets]
      (log/trace "Processing secret" secret)
      (let [name (:provider secret)
            provider-cfg (first (get by-name {:name name}))
            type (:type provider-cfg)
            provider (get-provider type)]
        (run provider {:config provider-cfg
                       :secret-spec secret
                       :template-dir template-dir})))))


(comment

  (get-provider :aa)

  (let [providers [{:name :dre-vault
                    :type :compoje.providers.vault/vault
                    :addr "VAULT_ADDR"}]
        by-name (set/index (into #{} providers) [:name])]
    by-name)



  )
