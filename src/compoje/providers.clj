(ns compoje.providers
  "Providers help to integrate with other systems to extend compoje."
  (:require [clojure.tools.logging :as log]))

(def ^:private provider-registry (atom {}))

(defn register-provider!
  "Register a provider with a given key.
   Prefer to use namespaced clojure keywords like: :compoje.providers/vault ."
  [key provider]
  (log/debug "Register provider" key "->" provider)
  (swap! provider-registry assoc key provider))

(defn registered-providers
  "Return the names of registered providers."
  []
  (keys @provider-registry))

(defprotocol SecretsProvider
  "Provider interface. Enhance compoje with other functionality."
  (provider-name [this] "Return the name of the provider. Can be a namespaced keyword.")
  (run [this context] "Provide compoje with secrets. Return a map with secret information."))

(defn get-provider
  (^SecretsProvider [key]
   (let [providers @provider-registry
         provider (get providers key)]
     (when-not (some? provider)
       (throw (ex-info (str "Provider does not exist: " key) {:provider key})))
     provider)))


(comment

  (get-provider :aa)

  )
