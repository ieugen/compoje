(ns integration.gen-ansible-vault-fixtures
  "Generate ansible-vault test fixtures with the reference implementation
   (ansible-core in a container, see test-resources/ansible_vault/Dockerfile).

   Run (needs Docker):
     clojure -M:dev -m integration.gen-ansible-vault-fixtures

   Writes test-resources/ansible_vault/generated/: password files, one
   .vault + .plain pair per case, inline.yml (encrypt_string values) and
   manifest.edn describing every case. Unit tests and bb tests read these."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pprint]
            [integration.ansible-vault-helpers :as h])
  (:import (java.util Random)))

(def out-dir "test-resources/ansible_vault/generated")

(def password-files
  {"pw-ascii.txt" "ansible-test-password\n"
   "pw-utf8.txt" "s€cure\n"
   "pw-wrong.txt" "not-the-password\n"})

(defn- binary [n seed]
  (let [b (byte-array n)] (.nextBytes (Random. seed) b) b))

(def cases
  [{:name "v11-yaml" :password-file "pw-ascii.txt"
    :plaintext "db:\n  user: admin\n  password: \"p@ss: w0rd\"\n"}
   {:name "v12-dev" :password-file "pw-ascii.txt" :vault-id "dev"
    :plaintext "foo bar\n"}
   {:name "utf8-password" :password-file "pw-utf8.txt"
    :plaintext "secret: value\n"}
   {:name "unicode" :password-file "pw-ascii.txt"
    :plaintext "私はガラスを食べられます。 Pot să mănânc sticlă. 🔐\n"}
   {:name "empty" :password-file "pw-ascii.txt" :plaintext ""}
   {:name "block-16" :password-file "pw-ascii.txt" :plaintext "0123456789abcdef"}
   {:name "block-32" :password-file "pw-ascii.txt"
    :plaintext "0123456789abcdef0123456789abcdef"}
   {:name "binary" :password-file "pw-ascii.txt" :plaintext (binary 4096 7)}
   {:name "fixed-salt" :password-file "pw-ascii.txt" :salt "fixed-salt"
    :plaintext "deterministic\n"}
   {:name "fixed-salt-dev" :password-file "pw-ascii.txt" :salt "fixed-salt" :vault-id "dev"
    :plaintext "deterministic\n"}])

(defn -main [& _]
  (fs/create-dirs out-dir)
  (let [c (h/start!)]
    (try
      (doseq [[f content] password-files]
        (spit (str (fs/path out-dir f)) content)
        (h/put-file! c (str "/work/" f) content))
      (doseq [{:keys [name password-file plaintext] :as kase} cases]
        (println "case" name)
        (spit (str (fs/path out-dir (str name ".vault")))
              (h/encrypt-file! c plaintext (str "/work/" password-file) kase))
        (fs/write-bytes (fs/path out-dir (str name ".plain"))
                        (if (bytes? plaintext) plaintext (.getBytes ^String plaintext "UTF-8"))))
      (println "inline.yml")
      (spit (str (fs/path out-dir "inline.yml"))
            (str "plain: value\n"
                 (h/encrypt-string! c "s3cret value" "db_password" "/work/pw-ascii.txt" {})
                 (h/encrypt-string! c "üñíçødé" "api_token" "/work/pw-ascii.txt" {:vault-id "dev"})
                 "nested:\n  list: [1, 2]\n"))
      (spit (str (fs/path out-dir "manifest.edn"))
            (with-out-str
              (pprint/pprint
               {:generated-by "test/integration/gen_ansible_vault_fixtures.clj"
                :ansible-core "2.21.6"
                :cases (mapv #(-> (dissoc % :plaintext)
                                  (assoc :vault (str (:name %) ".vault")
                                         :plain (str (:name %) ".plain")))
                             cases)
                :inline {:file "inline.yml"
                         :password-file "pw-ascii.txt"
                         :expected {"plain" "value"
                                    "db_password" "s3cret value"
                                    "api_token" "üñíçødé"
                                    "nested" {"list" [1 2]}}}})))
      (println "Wrote fixtures to" out-dir)
      (finally (h/stop! c)))))
