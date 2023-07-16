(ns compoje.providers.vault
  (:require [babashka.fs :as fs]
            [compoje.providers :as providers]
            [clojure.tools.logging :as log]
            [vault.client.http]
            [vault.core :as vault]
            [vault.secrets.kvv2 :as kv2]))

(defn token-path
  "Return the ^File to the vault token file.
   Vault client creates a token file at $HOME/.vault-token ."
  ([]
   (-> (fs/file (System/getProperty "user.home")
                ".vault-token")
       (fs/absolutize)
       (fs/file))))

(defn load-token!
  "Read the token file if exits and return it as ^String.
   Return nil if it does not exist.
   Throw error if can't read."
  ([]
   (load-token! (token-path)))
  ([f]
   (when (fs/exists? f)
     (slurp f))))

(defn authenticated-client
  "Try to get a vault authenticated client.
   Try multiple auth strategies in order.
   This is so it can work for local scripts and CI."
  ([]
   (let [token (or (System/getenv "VAULT_TOKEN")
                   (load-token!))
         app-role-id (System/getenv "VAULT_APP_ROLE_ID")
         app-secret-id (System/getenv "VAULT_APP_SECRET_ID")
         app-role? (and (some? app-role-id) (some? app-secret-id))
         addr (or (System/getenv "VAULT_ADDR")
                  (System/getProperty "vault.addr"))
         client (vault/new-client addr)
         state (atom {})]
     (when-not (System/getenv "VAULT_ADDR")
       (println "WARNING: Missing $VAULT_ADDR"))
     (if app-role?
       (let [client (vault/authenticate! client :app-role
                                         {:role-id app-role-id
                                          :secret-id app-secret-id})]
         (swap! state merge {:client client
                             :method :app-role}))
       (if token
         (let [client (vault/authenticate! client :token token)]
           (swap! state merge {:client client
                               :method :token}))
         (println "No valid auth method found.
                   Authenticate vault via one of:\n
                   * vault login -method=oidc\n
                   * bb access\n")))
     (let [{:keys [client method]} @state]
       (println "Using vault auth method" method)
       client))))

(defrecord VaultSecretsProvider []
  providers/SecretsProvider
  (run [_this context]
    (log/info "Provide secrets for" context)
    [])
  (provider-name [_this] ::vault))


(comment

  ::vault

  (def client (vault/new-client "https://vault.do.drevidence.com:8200") )

  (vault/authenticate! client :token (load-token!))

  (println client)

  (try
    (kv2/list-secrets client "DocSearch" "/")
    (catch Exception e
      (println e)))

  )