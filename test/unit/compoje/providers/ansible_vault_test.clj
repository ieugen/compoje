(ns unit.compoje.providers.ansible-vault-test
  (:require [ansible-vault.core :as av]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [compoje.providers :as providers]
            [compoje.providers.ansible-vault :as p]
            [unit.ansible-vault.fixtures :as fx]))

(def password (fx/password "pw-ascii.txt"))

(deftest decode-content-test
  (testing "whole-file vault, yaml: decrypted, parsed, keys keywordized"
    (is (= {:db {:user "admin" :password "p@ss: w0rd"}}
           (p/decode-content (fx/vault-text (fx/kase "v11-yaml")) nil password))))
  (testing "whole-file vault, raw: decrypted string"
    (is (= "foo bar\n" (p/decode-content (fx/vault-text (fx/kase "v12-dev")) "raw" password))))
  (testing "plain YAML with inline !vault values"
    (is (= {:plain "value" :db_password "s3cret value" :api_token "üñíçødé" :nested {:list [1 2]}}
           (p/decode-content (slurp (fx/path "inline.yml")) "yaml" password))))
  (testing "unknown format"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown ansible-vault secret format"
                          (p/decode-content "a: 1" "toml" password)))))

(deftest read-password-test
  (let [no-env (constantly nil)]
    (testing "password-file relative to the template dir, trimmed"
      (is (= "ansible-test-password"
             (p/read-password {:password-file "pw-ascii.txt"} fx/dir no-env))))
    (testing "password-env"
      (is (= "from-env" (p/read-password {:password-env "PW"} "." {"PW" "from-env"}))))
    (testing "password-env not set"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PW is not set"
                            (p/read-password {:password-env "PW"} "." no-env))))
    (testing "$ANSIBLE_VAULT_PASSWORD_FILE fallback"
      (is (= "ansible-test-password"
             (p/read-password {} "." {"ANSIBLE_VAULT_PASSWORD_FILE" (fx/path "pw-ascii.txt")}))))
    (testing "no password source"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no password"
                            (p/read-password {:name "v"} "." no-env))))
    (testing "empty password"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"empty"
                            (p/read-password {:password-env "PW"} "." {"PW" "  "}))))))

(deftest provide-secrets-test
  (providers/register-provider! (p/->AnsibleVaultSecretsProvider))
  (let [dir (fs/create-temp-dir {:prefix "compoje-av-"})]
    (try
      (fs/copy (fx/path "pw-ascii.txt") (fs/path dir "vault-password.txt"))
      (spit (str (fs/path dir "app.vault")) (av/encrypt "db_user: admin\ndb_password: \"p4ss\"\n" password))
      (fs/copy (fx/path "inline.yml") (fs/path dir "tokens.yml"))
      (let [results (providers/provide-secrets
                     {:template-dir (str dir)
                      :providers [{:name "demo"
                                   :type "compoje.providers.ansible-vault/ansible-vault"
                                   :password-file "vault-password.txt"}]
                      :secrets [{:name "app" :provider "demo" :file "app.vault"
                                 :local-path "generated/db_password.txt" :spit-key "db_password"}
                                {:name "tokens" :provider "demo" :file "tokens.yml"}]})]
        (testing "results keyed by secret name (keyword), usable from templates"
          (is (= {:db_user "admin" :db_password "p4ss"} (:app results)))
          (is (= "s3cret value" (get-in results [:tokens :db_password]))))
        (testing "local-path + spit-key writes one decrypted value"
          (is (= "p4ss" (slurp (str (fs/path dir "generated/db_password.txt")))))))
      (finally (fs/delete-tree dir)))))
