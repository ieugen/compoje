(ns compoje.providers.vault
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compoje.providers :as providers]
            [vault.client :as vault]
            [vault.auth.approle :as approle]
            [vault.secret.kv.v2 :as kv2]))

(defn authenticated-client
  "Try to get a vault authenticated client.
   Try multiple auth strategies in order.
   This is so it can work for local scripts and CI."
  ([& opts]
   (let [{:keys [token addr app-role-id app-secret-id]} opts
         token (or token
                   (System/getProperty "vault.token")
                   (System/getenv "VAULT_TOKEN")
                   (let [token-file (io/file (System/getProperty "user.home")
                                             ".vault-token")]
                     (when (.exists token-file)
                       (str/trim (slurp token-file)))))
         addr (or addr
                  (System/getenv "VAULT_ADDR"))
         app-role-id (or app-role-id
                         (System/getenv "VAULT_APP_ROLE_ID"))
         app-secret-id (or app-secret-id
                           (System/getenv "VAULT_APP_SECRET_ID"))
         app-role? (and (some? app-role-id)
                        (some? app-secret-id))
         _ (when-not addr
             (throw (ex-info "HashiCorp Vault provider: no server address. Set addr in the provider, or $VAULT_ADDR."
                             {})))
         client (vault/new-client addr)
         state (atom {})]
     (if app-role?
       (let [client (approle/login client :app-role
                                   {:role-id app-role-id
                                    :secret-id app-secret-id})]
         (swap! state merge {:client client
                             :method :app-role}))
       (if token
         (let [client (vault/authenticate! client token)]
           (swap! state merge {:client client
                               :method :token}))
         (log/debug "No valid auth method found.
                     Authenticate vault via one of:\n
                     * vault login -method=oidc\n
                     * bb access\n")))
     (let [{:keys [client method]} @state]
       (log/debug "Using vault auth method" method)
       client))))

(defn read-kvv2!
  "Fetch a kvv2 secret from vault.
   Associate the content back into the spec as :content."
  [client secret-spec]
  (log/trace "Fetch secret" secret-spec)
  (let [{:keys [mount-path secret-path opts]} secret-spec
        client (kv2/with-mount client mount-path)
        content (kv2/read-secret client secret-path opts)]
    (assoc secret-spec :content content)))

(defrecord VaultSecretsProvider []
  providers/SecretsProvider
  (run [_this context]
    (log/trace "Provide secrets for" context)
    (let [{:keys [config secret-spec template-dir]} context
          {:keys [name mount-path secret-path]} secret-spec]
      (try
        (let [client (authenticated-client config)
              secret (read-kvv2! client secret-spec)]
          (providers/secret->file! template-dir secret))
        (catch Exception e
          ;; vault-clj's message already has the path, HTTP status and Vault's errors
          (throw (ex-info (str "HashiCorp Vault secret " name " (" mount-path "/" secret-path "): " (ex-message e))
                          {:secret name :status (:vault.client/status (ex-data e))}
                          e))))))
  (provider-name [_this] ::vault))

(comment

  ::vault

  (def client (authenticated-client))

  (try
    (let [client (kv2/with-mount client "my-secret")]
      (kv2/list-secrets client "/"))
    (catch Exception e
      (println e))))