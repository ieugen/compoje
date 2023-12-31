(ns compoje.providers.vault
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [compoje.providers :as providers]
            [compoje.utils :as u]
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
         client (vault/new-client addr)
         state (atom {})]
     (when-not addr
       (log/error "Missing vault server address. It's required.
                   Pass value or set $VAULT_ADDR")
       (System/exit 1))
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

(defn secret->file!
  "Write a secret spec to file.
   It can spit whole contents to file or just a single key."
  ([secret-spec]
   (secret->file! (System/getProperty "user.dir") secret-spec))
  ([base-dir secret-spec]
   (let [{:keys [local-path content spit-key format]} secret-spec]
     ;; TODO: Also during dry-run, avoid writing secrets as files, just log ?!
     ;; TODO: Compute hash for secret to avoid needing a file on disk for hash
     (if local-path
       (let [path (-> (fs/file base-dir local-path)
                      (fs/absolutize)
                      (fs/file))
             path-str (str path)
             content (if (nil? spit-key)
                       ;; pretty print only edn maps
                       ;; https://github.com/amperity/vault-clj/issues/100
                       (case format
                         "edn" (u/pprint-str content)
                         "json" (json/generate-string content)
                         content)
                       (get content spit-key))]
         (fs/create-dirs (fs/parent path))
         (log/trace "Write secret to" path-str)
         (spit path content))
       (log/trace "Skip writing secret. Missing :local-path option."))
     content)))

(defrecord VaultSecretsProvider []
  providers/SecretsProvider
  (run [_this context]
    (log/trace "Provide secrets for" context)
    (try
      (let [{:keys [config secret-spec template-dir]} context
            client (authenticated-client config)
            secret (read-kvv2! client secret-spec)]
        (secret->file! template-dir secret))
      (catch Exception e
        (let [{:keys [type error status]} (ex-data e)]
          (if (= type :vault.client.api-util/api-error)
            (do
              (log/error "Exception reading secret: " error
                         ". Status:" status)
              (System/exit -1))
            (do
              (log/error "Exception reading secret" e)
              (System/exit -1)))))))
  (provider-name [_this] ::vault))

(comment

  ::vault

  (def client (authenticated-client))

  (try
    (let [client (kv2/with-mount client "my-secret")]
      (kv2/list-secrets client "/"))
    (catch Exception e
      (println e))))