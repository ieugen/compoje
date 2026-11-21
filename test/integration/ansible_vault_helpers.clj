(ns integration.ansible-vault-helpers
  "Drive a reference ansible-vault (ansible-core in a container) from Clojure.
   Used by integration tests and by test/integration/gen_ansible_vault_fixtures.clj."
  (:require [babashka.fs :as fs]
            [clj-test-containers.core :as tc]
            [clojure.string :as str])
  (:import (java.io InputStream)
           (org.testcontainers.containers GenericContainer)
           (org.testcontainers.utility ThrowingFunction)))

(def dockerfile "test-resources/ansible_vault/Dockerfile")

(defn start!
  "Build (cached by docker) and start the ansible-core container."
  []
  (-> (tc/create-from-docker-file {:docker-file dockerfile
                                   :command ["sleep" "infinity"]})
      tc/start!))

(defn stop! [container]
  (tc/stop! container))

(defn put-file!
  "Write `content` (string as UTF-8, or bytes) to `container-path`."
  [container container-path content]
  (let [tmp (fs/create-temp-file {:prefix "ansible-vault-"})]
    (try
      (if (bytes? content)
        (fs/write-bytes tmp content)
        (spit (str tmp) content))
      (tc/copy-file-to-container! container {:path (str tmp)
                                             :container-path container-path
                                             :type :host-path})
      container-path
      (finally (fs/delete-if-exists tmp)))))

(defn get-bytes
  "Read a file from the container as bytes."
  ^bytes [container container-path]
  (.copyFileFromContainer ^GenericContainer (:container container)
                          ^String container-path
                          (reify ThrowingFunction
                            (apply [_ in] (.readAllBytes ^InputStream in)))))

(defn get-text [container container-path]
  (String. (get-bytes container container-path) "UTF-8"))

(defn ansible-vault!
  "Run ansible-vault with `args` in the container. `env` is a map of extra
   environment variables (e.g. {\"ANSIBLE_VAULT_ENCRYPT_SALT\" \"...\"}).
   Returns {:exit-code :stdout :stderr}."
  ([container args] (ansible-vault! container args {}))
  ([container args env]
   (tc/execute-command! container
                        (concat ["env"]
                                (map (fn [[k v]] (str k "=" v)) env)
                                ["ansible-vault"]
                                args))))

(defn ansible-vault-ok!
  "Like ansible-vault! but throws on a non-zero exit code."
  [& args]
  (let [{:keys [exit-code stderr] :as result} (apply ansible-vault! args)]
    (when-not (zero? exit-code)
      (throw (ex-info (str "ansible-vault failed: " (str/trim stderr)) result)))
    result))

(defn vault-id-arg
  "ansible-vault password argument: --vault-id [label@]password-file."
  [password-file vault-id]
  (if vault-id
    ["--vault-id" (str vault-id "@" password-file)]
    ["--vault-password-file" password-file]))

(defn encrypt-file!
  "Encrypt `plaintext` (string or bytes) with ansible-vault; returns the vault text.
   opts: :vault-id, :salt (sets ANSIBLE_VAULT_ENCRYPT_SALT)."
  [container plaintext password-file {:keys [vault-id salt]}]
  (let [in (put-file! container (str "/work/plain-" (random-uuid)) plaintext)
        out (str in ".vault")]
    (ansible-vault-ok! container
                       (concat ["encrypt"] (vault-id-arg password-file vault-id)
                               ["--output" out in])
                       (cond-> {} salt (assoc "ANSIBLE_VAULT_ENCRYPT_SALT" salt)))
    (get-text container out)))

(defn decrypt-file
  "Decrypt `vaulttext` with ansible-vault. Returns {:exit-code :stderr :plaintext bytes-or-nil}."
  [container vaulttext password-file {:keys [vault-id]}]
  (let [in (put-file! container (str "/work/enc-" (random-uuid) ".vault") vaulttext)
        out (str in ".plain")
        result (ansible-vault! container
                               (concat ["decrypt"] (vault-id-arg password-file vault-id)
                                       ["--output" out in]))]
    (assoc result :plaintext (when (zero? (:exit-code result))
                               (get-bytes container out)))))

(defn encrypt-string!
  "ansible-vault encrypt_string --name `var-name`; returns the YAML snippet."
  [container value var-name password-file {:keys [vault-id]}]
  (:stdout (ansible-vault-ok! container
                              (concat ["encrypt_string"] (vault-id-arg password-file vault-id)
                                      ["--name" var-name value]))))
