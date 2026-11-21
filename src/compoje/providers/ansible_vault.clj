;; Copyright Eugen Stan <eugen@ieugen.ro>
(ns compoje.providers.ansible-vault
  "compoje secrets provider for ansible-vault encrypted data.

   Glue only: the vault format is implemented by the ansible-vault.* namespaces
   (ansible-vault.core, ansible-vault.crypto, ansible-vault.yamlstar), which
   have no compoje dependencies and can be extracted as a library.

   Provider config (x-compoje providers):
     - name: my-vault
       type: compoje.providers.ansible-vault/ansible-vault
       password-file: vault-password.txt   # relative to the stack dir, ~ allowed
       password-env: MY_VAULT_PASSWORD      # alternative: env var with the password
       vault-id: dev                        # optional label for the password
   Without password-file/password-env, $ANSIBLE_VAULT_PASSWORD_FILE is used.

   Secret spec (x-compoje secrets):
     - name: app                 # result is available in templates as <{ app... }>
       provider: my-vault
       file: secrets/app.yml     # whole-file encrypted, or YAML with !vault values
       format: yaml              # yaml (default): parsed, keys keywordized; raw: string
       local-path: out/pw.txt    # optional: write the content (or spit-key) to a file
       spit-key: db_password"
  (:require [ansible-vault.core :as av]
            [ansible-vault.yamlstar :as avy]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [compoje.providers :as providers]))

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

(defn read-password
  "Find the vault password for a provider config.
   Order: password-file, password-env, $ANSIBLE_VAULT_PASSWORD_FILE.
   Password files are trimmed, like ansible does. `getenv` is injectable for tests."
  ([config template-dir]
   (read-password config template-dir #(System/getenv %)))
  ([{:keys [password-file password-env name]} template-dir getenv]
   (let [home (System/getProperty "user.home")
         from-file #(str/trim (slurp (resolve-path template-dir % home)))
         password (cond
                    password-file (from-file password-file)
                    password-env (or (getenv password-env)
                                     (throw (ex-info (str "ansible-vault provider " name
                                                          ": environment variable " password-env " is not set")
                                                     {:provider name :password-env password-env})))
                    (getenv "ANSIBLE_VAULT_PASSWORD_FILE") (from-file (getenv "ANSIBLE_VAULT_PASSWORD_FILE"))
                    :else (throw (ex-info (str "ansible-vault provider " name
                                               ": no password. Set password-file or password-env, or $ANSIBLE_VAULT_PASSWORD_FILE")
                                          {:provider name})))]
     (when (str/blank? password)
       (throw (ex-info (str "ansible-vault provider " name ": the vault password is empty")
                       {:provider name})))
     password)))

(defn vault-secrets
  "Secrets in the form ansible-vault.core expects."
  [password {:keys [vault-id]}]
  (if vault-id [[(str vault-id) password]] password))

^:rct/test
(comment
  (vault-secrets "pw" {}) ;=> "pw"
  (vault-secrets "pw" {:vault-id "dev"}) ;=> [["dev" "pw"]]
  )

(defn decode-content
  "Turn file text into secret content. Whole-file vaults are decrypted first.
   format \"raw\" returns the string; otherwise (\"yaml\", the default) the YAML
   is parsed, inline !vault values are decrypted and keys are keywordized."
  [text format secrets]
  (let [text (if (av/encrypted? text) (av/decrypt text secrets) text)]
    (case (or format "yaml")
      "raw" text
      "yaml" (walk/keywordize-keys (avy/load-yaml text secrets))
      (throw (ex-info (str "Unknown ansible-vault secret format: " format ". Use yaml or raw.")
                      {:format format})))))

(defn load-secret
  "Read and decode the secret file of `secret-spec`."
  [{:keys [file format name] :as _secret-spec} template-dir secrets]
  (when-not file
    (throw (ex-info (str "ansible-vault secret " name ": missing file") {:secret name})))
  (let [path (resolve-path template-dir file (System/getProperty "user.home"))]
    (log/debug "Decrypt ansible-vault secret" name "from" path)
    (decode-content (slurp path) format secrets)))

(defrecord AnsibleVaultSecretsProvider []
  providers/SecretsProvider
  (run [_this {:keys [config secret-spec template-dir]}]
    (let [secrets (vault-secrets (read-password config template-dir) config)
          content (load-secret secret-spec template-dir secrets)]
      (providers/secret->file! template-dir (assoc secret-spec :content content))))
  (provider-name [_this] ::ansible-vault))
