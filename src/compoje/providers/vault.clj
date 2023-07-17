(ns compoje.providers.vault
  (:require [babashka.fs :as fs]
            [compoje.providers :as providers]
            [clojure.tools.logging :as log]
            [vault.client.http]
            [vault.core :as vault]
            [vault.secrets.kvv2 :as kv2]
            [clojure.pprint :as pp]))

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
  ([& opts]
   (let [{:keys [token addr app-role-id app-secret-id]} opts
         token (or token
                   (System/getenv "VAULT_TOKEN")
                   (load-token!))
         app-role-id (or app-role-id
                         (System/getenv "VAULT_APP_ROLE_ID"))
         app-secret-id (or app-secret-id
                           (System/getenv "VAULT_APP_SECRET_ID"))
         app-role? (and (some? app-role-id)
                        (some? app-secret-id))
         addr (or addr
                  (System/getenv "VAULT_ADDR"))
         client (vault/new-client addr)
         state (atom {})]
     (when-not addr
       (log/error "Missing vault server address. It's required.
                   Pass value or set $VAULT_ADDR")
       (System/exit 1))
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
        content (kv2/read-secret client mount-path secret-path opts)]
    (assoc secret-spec :content content)))

(defn pprint-str
  "Pretty print to string"
  [content]
  (let [out (java.io.StringWriter.)]
    (pp/pprint content out)
    (.toString out)))


(defn secret->file!
  "Write a secret spec to file.
   It can spit whole contents to file or just a single key."
  ([secret-spec]
   (secret->file! (System/getProperty "user.dir") secret-spec))
  ([base-dir secret-spec]
   (let [{:keys [local-path content spit-key]} secret-spec
         path (-> (fs/file base-dir local-path)
                  (fs/absolutize)
                  (fs/file))
         content (if (nil? spit-key)
                     ;; pretty print only edn maps
                   (pprint-str content)
                   (get content spit-key))]
     (log/trace "Write secret to" (str path))
     (fs/create-dirs (fs/parent path))
     (spit path content))))

(defrecord VaultSecretsProvider []
  providers/SecretsProvider
  (run [_this context]
    (log/trace "Provide secrets for" context)
    (let [{:keys [config secret-spec template-dir]} context
          client (authenticated-client config)
          secret (read-kvv2! client secret-spec)]
      (secret->file! template-dir secret)))
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