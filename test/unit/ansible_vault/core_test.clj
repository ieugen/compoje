(ns unit.ansible-vault.core-test
  "Unit tests for ansible-vault.core.

   Test names mirror ansible-core's test/units/parsing/vault/test_vault.py
   (TestUnhexlify, TestParseVaulttext, TestVaultCipherAes256, TestMatchSecrets,
   TestVaultLib) for the cases that apply to a decrypt/encrypt library.
   The data comes from our own fixtures (test/integration/gen_ansible_vault_fixtures.clj),
   generated with real ansible-core."
  (:require [ansible-vault.core :as av]
            [ansible-vault.crypto :as crypto]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [unit.ansible-vault.fixtures :as fx])
  (:import (java.util Arrays)))

(defn- error-type [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

;; ------------------------------------------------------------ fixtures

(deftest fixtures-decrypt
  (doseq [{:keys [name password-file vault-id] :as kase} (:cases fx/manifest)]
    (testing name
      (let [result (av/decrypt-and-get-vault-id (fx/vault-text kase) (fx/password password-file))]
        (is (Arrays/equals (fx/plain-bytes kase) ^bytes (:plaintext result)))
        (is (= (if vault-id "1.2" "1.1") (:version (av/parse-envelope (fx/vault-text kase)))))
        (when vault-id
          (is (= vault-id (:vault-id (av/parse-envelope (fx/vault-text kase))))))))))

(deftest fixed-salt-encrypt-matches-ansible-byte-for-byte
  (doseq [{:keys [name password-file vault-id salt] :as kase} (:cases fx/manifest)
          :when salt]
    (testing name
      (is (= (fx/vault-text kase)
             (av/encrypt (fx/plain-bytes kase) (fx/password password-file)
                         {:vault-id vault-id :salt salt}))))))

(deftest wrong-password
  (is (= :ansible-vault/no-matching-secret
         (error-type #(av/decrypt (fx/vault-text (fx/kase "v11-yaml")) (fx/password "pw-wrong.txt"))))))

(deftest crlf-envelope
  (let [kase (fx/kase "v11-yaml")
        crlf (str/replace (fx/vault-text kase) "\n" "\r\n")]
    (is (Arrays/equals (fx/plain-bytes kase)
                       (av/decrypt-bytes crlf (fx/password "pw-ascii.txt"))))))

(deftest tampered-hmac
  (let [text (fx/vault-text (fx/kase "v11-yaml"))
        ;; change the last hex digit of the body
        i (- (count (str/trimr text)) 1)
        tampered (str (subs text 0 i) (if (= \0 (.charAt ^String text i)) "1" "0") (subs text (inc i)))]
    (is (= :ansible-vault/no-matching-secret
           (error-type #(av/decrypt tampered (fx/password "pw-ascii.txt")))))))

(deftest round-trip-sizes
  (doseq [n (range 0 65)]
    (let [data (byte-array (map #(unchecked-byte (* 7 %)) (range n)))]
      (is (Arrays/equals data (av/decrypt-bytes (av/encrypt data "pw") "pw"))
          (str "size " n)))))

;; ------------------------------------------------------------ TestUnhexlify

(deftest test-unhexlify
  (testing "test"
    (is (= "some text to hexlify"
           (crypto/utf8-str (crypto/unhexlify (crypto/hexlify (crypto/utf8-bytes "some text to hexlify")))))))
  (testing "test_odd_length"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Vault format unhexlify error"
                          (crypto/unhexlify "123456789abcdefghijklmnopqrstuvwxyz"))))
  (testing "test_nonhex"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Vault format unhexlify error.*Non-hexadecimal digit found"
                          (crypto/unhexlify "6z36316566653264333665333637623064303639353237620a636366633565663263336335656532")))))

;; ------------------------------------------------------------ TestParseVaulttext

(deftest test-parse-vaulttext
  (testing "test: salt, hmac and ciphertext are byte arrays"
    (let [{:keys [salt hmac ciphertext]} (av/parse-vaulttext (:body (av/parse-envelope (fx/vault-text (fx/kase "v11-yaml")))))]
      (is (every? bytes? [salt hmac ciphertext]))
      (is (= 32 (alength ^bytes salt)))
      (is (= 32 (alength ^bytes hmac)))))
  (testing "test_non_hex"
    (let [body (:body (av/parse-envelope (fx/vault-text (fx/kase "v11-yaml"))))
          bad (str (subs body 0 7) "J" (subs body 8))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Non-hexadecimal digit found"
                            (av/parse-vaulttext bad))))))

;; ------------------------------------------------------------ TestVaultCipherAes256

(deftest test-create-key-known-cryptography
  (let [salt (crypto/utf8-bytes (apply str (repeat 32 "q")))
        k1 (crypto/derive-keys "hunter42" salt)
        k2 (crypto/derive-keys "hunter42" salt)]
    (is (every? #(Arrays/equals ^bytes (% k1) ^bytes (% k2)) [:key1 :key2 :iv]))
    (is (= [32 32 16] (map #(alength ^bytes (% k1)) [:key1 :key2 :iv])))))

;; ------------------------------------------------------------ TestMatchSecrets

(deftest test-match-secrets
  (testing "test_empty_secrets"
    (is (= [] (av/order-secrets [] "dev"))))
  (testing "test_single_match"
    (is (= [["dev" "a"]] (av/order-secrets [["dev" "a"]] "dev"))))
  (testing "test_no_matches: all secrets are still tried, in order"
    (is (= [["a" 1] ["b" 2]] (av/order-secrets [["a" 1] ["b" 2]] "dev"))))
  (testing "test_multiple_matches: matches first, keeping their order"
    (is (= [["dev" 1] ["dev" 3] ["x" 2]] (av/order-secrets [["dev" 1] ["x" 2] ["dev" 3]] "dev")))))

;; ------------------------------------------------------------ TestVaultLib

(deftest test-vault-lib
  (testing "test_encrypt"
    (let [text (av/encrypt "foo" "test-vault-password")]
      (is (av/encrypted? text))
      (is (str/starts-with? text "$ANSIBLE_VAULT;1.1;AES256\n"))))
  (testing "test_encrypt_vault_id"
    (is (str/starts-with? (av/encrypt "foo" "pw" {:vault-id "test_id"}) "$ANSIBLE_VAULT;1.2;AES256;test_id\n")))
  (testing "test_encrypt_bytes"
    (is (= "foo" (av/decrypt (av/encrypt (crypto/utf8-bytes "foo") "pw") "pw"))))
  (testing "test_encrypt_no_secret_empty_secrets"
    (is (= :ansible-vault/empty-password (error-type #(av/encrypt "foo" "")))))
  (testing "test_format_vaulttext_envelope"
    (let [text (av/format-envelope "ansible" "TEST" {:vault-id "default"})
          [header] (str/split-lines text)
          parts (str/split header #";")]
      (is (= ["$ANSIBLE_VAULT" "1.1" "TEST"] parts))
      (is (= {:version "1.1" :cipher "TEST" :vault-id "default" :body "ansible"}
             (av/parse-envelope text)))))
  (testing "test_parse_vaulttext_envelope"
    (is (= {:version "9.9" :cipher "TEST" :vault-id "default" :body "ansible"}
           (av/parse-envelope "$ANSIBLE_VAULT;9.9;TEST\nansible"))))
  (testing "test_parse_vaulttext_envelope_crlf"
    (is (= {:version "9.9" :cipher "TEST" :vault-id "default" :body "ansible"}
           (av/parse-envelope "$ANSIBLE_VAULT;9.9;TEST\r\nansible"))))
  (testing "test_encrypt_decrypt_aes256"
    (is (= "foobar" (av/decrypt (av/encrypt "foobar" "pw") "pw"))))
  (testing "test_encrypt_decrypt_aes256_none_secrets"
    (is (= :ansible-vault/no-secrets (error-type #(av/decrypt (av/encrypt "foobar" "pw") nil)))))
  (testing "test_encrypt_decrypt_aes256_empty_secrets"
    (is (= :ansible-vault/no-secrets (error-type #(av/decrypt (av/encrypt "foobar" "pw") [])))))
  (testing "test_encrypt_decrypt_aes256_multiple_secrets_all_wrong"
    (is (= :ansible-vault/no-matching-secret
           (error-type #(av/decrypt (av/encrypt "foobar" "pw") [["a" "x"] ["b" "y"]])))))
  (testing "test_encrypt_decrypt_aes256_multiple_secrets_one_valid"
    (is (= "foobar" (av/decrypt (av/encrypt "foobar" "pw") [["a" "x"] ["b" "pw"] ["c" "z"]]))))
  (testing "test_encrypt_decrypt_aes256_existing_vault"
    (is (= :ansible-vault/already-encrypted
           (error-type #(av/encrypt (av/encrypt "foobar" "pw") "pw")))))
  (testing "test_decrypt_and_get_vault_id"
    (let [kase (fx/kase "v12-dev")
          {:keys [plaintext vault-id]} (av/decrypt-and-get-vault-id
                                        (fx/vault-text kase)
                                        [["other" "nope"] ["dev" (fx/password "pw-ascii.txt")]])]
      (is (= "dev" vault-id))
      (is (Arrays/equals (fx/plain-bytes kase) ^bytes plaintext))))
  (testing "test_decrypt_non_default_1_2: a 'default' secret decrypts a labelled 1.2 vault"
    (let [kase (fx/kase "v12-dev")]
      (is (Arrays/equals (fx/plain-bytes kase)
                         (av/decrypt-bytes (fx/vault-text kase) (fx/password "pw-ascii.txt"))))
      (is (= {:version "1.2" :vault-id "dev"}
             (select-keys (av/parse-envelope (fx/vault-text kase)) [:version :vault-id])))))
  (testing "test_decrypt_decrypted"
    (is (= :ansible-vault/not-encrypted (error-type #(av/decrypt "ansible" "pw"))))
    (is (= :ansible-vault/not-encrypted (error-type #(av/decrypt (crypto/utf8-bytes "ansible") "pw")))))
  (testing "unsupported cipher"
    (is (= :ansible-vault/unsupported-cipher
           (error-type #(av/decrypt (str/replace (av/encrypt "x" "pw") "AES256" "AES") "pw"))))))
