(ns integration.ansible-vault-test
  "Cross-implementation tests: ansible-core (python, in a container) against
   ansible-vault.core (Clojure), in both directions. Needs Docker."
  (:require [ansible-vault.core :as av]
            [ansible-vault.crypto :as crypto]
            [ansible-vault.yamlstar :as avy]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [integration.ansible-vault-helpers :as h])
  (:import (java.util Arrays Random)))

(def ^:dynamic *c* nil)

;; password files as written in the container -> effective password
;; (ansible strips surrounding whitespace from password files)
(def passwords
  {"/work/pw-ascii" {:content "ansible-test-password\n" :password "ansible-test-password"}
   "/work/pw-utf8" {:content "s€cure" :password "s€cure"}
   "/work/pw-space" {:content "  spaced password \n\n" :password "spaced password"}})

(defn- binary [n seed]
  (let [b (byte-array n)] (.nextBytes (Random. seed) b) b))

(def plaintexts
  {:ascii "foo bar\n"
   :yaml "db:\n  user: admin\n  password: \"p@ss: w0rd\"\nlist:\n  - a\n  - b\n"
   :unicode "私はガラスを食べられます。 Pot să mănânc sticlă și ea nu mă rănește. 🔐\n"
   :empty ""
   :block-16 "0123456789abcdef"
   :block-32 "0123456789abcdef0123456789abcdef"
   :binary-1mib (binary (* 1024 1024) 42)})

(def vault-ids [nil "dev" "prod"])

(defn- ->bytes [p] (if (bytes? p) p (crypto/utf8-bytes p)))

(defn with-container [f]
  (let [c (h/start!)]
    (try
      (doseq [[path {:keys [content]}] passwords]
        (h/put-file! c path content))
      (binding [*c* c] (f))
      (finally (h/stop! c)))))

(use-fixtures :once with-container)

(deftest python-encrypts-clojure-decrypts
  (doseq [[pw-file {:keys [password]}] passwords
          vault-id vault-ids
          [label plaintext] plaintexts]
    (testing (str label " " pw-file " vault-id=" vault-id)
      (let [vaulttext (h/encrypt-file! *c* plaintext pw-file {:vault-id vault-id})
            {:keys [version]} (av/parse-envelope vaulttext)
            result (av/decrypt-and-get-vault-id vaulttext password)]
        (is (= (if vault-id "1.2" "1.1") version))
        (is (Arrays/equals (->bytes plaintext) ^bytes (:plaintext result)))))))

(deftest clojure-encrypts-python-decrypts
  (doseq [[pw-file {:keys [password]}] passwords
          vault-id vault-ids
          [label plaintext] plaintexts]
    (testing (str label " " pw-file " vault-id=" vault-id)
      (let [vaulttext (av/encrypt plaintext password {:vault-id vault-id})
            {:keys [exit-code stderr plaintext] :as r} (h/decrypt-file *c* vaulttext pw-file {:vault-id vault-id})]
        (is (zero? exit-code) stderr)
        (is (Arrays/equals (->bytes (get plaintexts label)) ^bytes plaintext))))))

(deftest byte-exact-with-fixed-salt
  (doseq [salt ["fixed-salt" "a much longer fixed salt value with spaces 0123456789"]
          vault-id vault-ids
          [label plaintext] (dissoc plaintexts :binary-1mib)]
    (testing (str label " salt=" salt " vault-id=" vault-id)
      (is (= (h/encrypt-file! *c* plaintext "/work/pw-ascii" {:vault-id vault-id :salt salt})
             (av/encrypt plaintext "ansible-test-password" {:vault-id vault-id :salt salt}))))))

(deftest vault-id-matching
  (let [vaulttext (h/encrypt-file! *c* "matched\n" "/work/pw-ascii" {:vault-id "dev"})]
    (testing "the secret whose vault-id matches the header is used"
      (is (= {:vault-id "dev" :plaintext "matched\n"}
             (-> (av/decrypt-and-get-vault-id vaulttext [["prod" "wrong"] ["dev" "ansible-test-password"]])
                 (update :plaintext crypto/utf8-str)))))
    (testing "the vault-id is only a hint: any secret that passes the HMAC works"
      (is (= "matched\n" (av/decrypt vaulttext [["prod" "ansible-test-password"]]))))
    (testing "multiple secrets, all wrong"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Decryption failed"
                            (av/decrypt vaulttext [["dev" "nope"] ["prod" "nope2"]]))))))

(deftest encrypt-string-inline-values
  (let [yaml (str "plain: value\n"
                  (h/encrypt-string! *c* "s3cret value" "db_password" "/work/pw-ascii" {})
                  (h/encrypt-string! *c* "üñíçødé" "api_token" "/work/pw-ascii" {:vault-id "dev"})
                  "nested:\n  list: [1, 2]\n")]
    (is (= {"plain" "value"
            "db_password" "s3cret value"
            "api_token" "üñíçødé"
            "nested" {"list" [1 2]}}
           (avy/load-yaml yaml "ansible-test-password")))))

(deftest negative-cases
  (testing "wrong password: ansible-vault rejects a file Clojure encrypted"
    (let [vaulttext (av/encrypt "secret\n" "some-other-password")
          {:keys [exit-code stderr]} (h/decrypt-file *c* vaulttext "/work/pw-ascii" {})]
      (is (not (zero? exit-code)))
      (is (re-find #"(?i)decryption failed" stderr))))
  (testing "tampered ciphertext fails in both implementations"
    (let [vaulttext (h/encrypt-file! *c* "tamper me\n" "/work/pw-ascii" {})
          lines (str/split-lines vaulttext)
          ;; flip one hex digit in the last body line (inside the ciphertext part)
          last-line (last lines)
          i (- (count last-line) 3)
          flipped (str (subs last-line 0 i)
                       (if (= \0 (.charAt ^String last-line i)) "1" "0")
                       (subs last-line (inc i)))
          tampered (str (str/join "\n" (concat (butlast lines) [flipped])) "\n")]
      (is (not= vaulttext tampered))
      (is (thrown? clojure.lang.ExceptionInfo (av/decrypt tampered "ansible-test-password")))
      (is (not (zero? (:exit-code (h/decrypt-file *c* tampered "/work/pw-ascii" {}))))))))
