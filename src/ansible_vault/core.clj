(ns ansible-vault.core
  "Read and write the ansible-vault encrypted format (AES256 cipher, vault format 1.1 and 1.2).

   Compatible with ansible-core's lib/ansible/parsing/vault/__init__.py:
   files and `!vault` values produced by `ansible-vault` decrypt here, and
   output of `encrypt` decrypts with `ansible-vault`.

   Uses only the JDK and clojure.core; runs on the JVM and in Babashka.

   Secrets (passwords) are given as:
   - a password string (vault-id \"default\"),
   - a map {vault-id password}, or
   - a sequence of [vault-id password] pairs (tried in order).
   Secrets whose vault-id matches the envelope header are tried first.

   Errors are ex-info with :type
   :ansible-vault/not-encrypted, :ansible-vault/already-encrypted,
   :ansible-vault/format-error, :ansible-vault/unsupported-cipher,
   :ansible-vault/no-secrets, :ansible-vault/empty-password,
   :ansible-vault/no-matching-secret.

   File format:
   https://docs.ansible.com/ansible/latest/vault_guide/vault_using_encrypted_content.html#format-of-files-encrypted-with-ansible-vault"
  (:require [ansible-vault.crypto :as crypto]
            [clojure.string :as str]))

(def header "$ANSIBLE_VAULT")
(def cipher-name "AES256")
(def default-vault-id "default")

(defn- error
  ([type msg] (error type msg {}))
  ([type msg data] (ex-info msg (assoc data :type type))))

(defn- ->text
  "Vault data is ASCII text; accept a string or bytes."
  ^String [data]
  (cond
    (string? data) data
    (bytes? data) (crypto/utf8-str data)
    :else (throw (IllegalArgumentException.
                  (str "Expected a string or bytes, got " (type data))))))

(defn encrypted?
  "True if `data` (string or bytes) is vault encrypted: pure ASCII that starts
   with $ANSIBLE_VAULT. Anything else, including non-strings, is false."
  [data]
  (cond
    (string? data) (and (every? #(< (int %) 128) data)
                        (str/starts-with? data header))
    (bytes? data) (and (every? #(>= % 0) data)
                       (encrypted? (crypto/utf8-str data)))
    :else false))

^:rct/test
(comment
  (encrypted? "foobar") ;=> false
  (encrypted? (.getBytes "foobar")) ;=> false
  (encrypted? (str "$ANSIBLE_VAULT;9.9;TEST\n" (crypto/hexlify (.getBytes "ansible")))) ;=> true
  (encrypted? (.getBytes (str "$ANSIBLE_VAULT;9.9;TEST\n" (crypto/hexlify (.getBytes "ansible"))))) ;=> true
  (encrypted? "$ANSIBLE_VAULT;9.9;TEST\nァ ア ィ イ ゥ ウ ェ エ ォ オ カ ガ キ ギ ク グ ケ ") ;=> false
  (encrypted? (.getBytes "$ANSIBLE_VAULT;9.9;TEST\nァ ア ィ イ ゥ ウ" "UTF-8")) ;=> false
  (encrypted? nil) ;=> false
  (encrypted? 42) ;=> false
  )

(defn parse-envelope
  "Split vault text into its header fields and the hex body.
   Returns {:version :cipher :vault-id :body}. Tolerates CRLF line endings
   and indented body lines (inline !vault values). :vault-id defaults to
   \"default\" when the header has none (format 1.1)."
  [vaulttext]
  (let [lines (str/split-lines (->text vaulttext))
        parts (mapv str/trim (str/split (str/trim (first lines)) #";"))]
    (when-not (and (>= (count parts) 3) (= header (first parts)))
      (throw (crypto/format-error "Vault envelope format error: bad header"
                                  {:header (first lines)})))
    {:version (nth parts 1)
     :cipher (nth parts 2)
     :vault-id (if (>= (count parts) 4) (nth parts 3) default-vault-id)
     :body (apply str (map str/trim (rest lines)))}))

^:rct/test
(comment
  (parse-envelope "$ANSIBLE_VAULT;9.9;TEST\nansible")
  ;=> {:version "9.9", :cipher "TEST", :vault-id "default", :body "ansible"}
  (parse-envelope "$ANSIBLE_VAULT;9.9;TEST\r\nansible")
  ;=> {:version "9.9", :cipher "TEST", :vault-id "default", :body "ansible"}
  (parse-envelope "$ANSIBLE_VAULT;1.2;AES256;dev\n  6162\n  6364\n")
  ;=> {:version "1.2", :cipher "AES256", :vault-id "dev", :body "61626364"}
  (try (parse-envelope "not a vault") (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
  ;=> :ansible-vault/format-error
  )

(defn format-envelope
  "Header + body split into 80 character lines, newline terminated.
   A vault-id other than \"default\" selects format 1.2 and is written in the header."
  ([body cipher] (format-envelope body cipher {}))
  ([^String body cipher {:keys [version vault-id]}]
   (let [version (if (and vault-id (not= vault-id default-vault-id))
                   "1.2"
                   (or version "1.1"))
         header-line (str/join ";" (cond-> [header version cipher]
                                     (and (= "1.2" version) vault-id) (conj vault-id)))
         body-lines (map #(subs body % (min (count body) (+ % 80)))
                         (range 0 (count body) 80))]
     (str (str/join "\n" (cons header-line body-lines)) "\n"))))

^:rct/test
(comment
  (format-envelope "ansible" "TEST") ;=> "$ANSIBLE_VAULT;1.1;TEST\nansible\n"
  (format-envelope "ansible" "TEST" {:vault-id "default"}) ;=> "$ANSIBLE_VAULT;1.1;TEST\nansible\n"
  (format-envelope "ansible" "TEST" {:vault-id "dev"}) ;=> "$ANSIBLE_VAULT;1.2;TEST;dev\nansible\n"
  (map count (str/split-lines (format-envelope (apply str (repeat 170 "a")) "AES256"))) ;=> (25 80 80 10)
  (let [env (parse-envelope (format-envelope "ansible" "TEST" {:vault-id "dev"}))]
    [(:body env) (:cipher env) (:vault-id env) (:version env)])
  ;=> ["ansible" "TEST" "dev" "1.2"]
  )

(defn parse-vaulttext
  "Decode the hex body into {:salt :hmac :ciphertext} byte arrays."
  [^String body]
  (let [inner (crypto/utf8-str (crypto/unhexlify body))
        parts (str/split inner #"\n" 3)]
    (when-not (= 3 (count parts))
      (throw (crypto/format-error "Vault vaulttext format error: expected salt, hmac and ciphertext"
                                  {:parts (count parts)})))
    (let [[salt hmac ciphertext] parts]
      {:salt (crypto/unhexlify salt)
       :hmac (crypto/unhexlify hmac)
       :ciphertext (crypto/unhexlify ciphertext)})))

^:rct/test
(comment
  (let [{:keys [salt hmac ciphertext]} (parse-vaulttext (crypto/hexlify (.getBytes "0102\n0a0b\nff")))]
    [(vec salt) (vec hmac) (vec ciphertext)])
  ;=> [[1 2] [10 11] [-1]]
  (try (parse-vaulttext (crypto/hexlify (.getBytes "0102"))) (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
  ;=> :ansible-vault/format-error
  )

(defn normalize-secrets
  "Secrets as a vector of [vault-id password] pairs.
   Accepts a password string, a {vault-id password} map or [vault-id password] pairs.
   Throws :ansible-vault/no-secrets for nil/empty and
   :ansible-vault/empty-password for an empty password."
  [secrets]
  (let [pairs (cond
                (string? secrets) [[default-vault-id secrets]]
                (map? secrets) (vec secrets)
                :else (vec secrets))]
    (when (empty? pairs)
      (throw (error :ansible-vault/no-secrets "A vault password must be specified")))
    (doseq [[vault-id password] pairs]
      (when (empty? password)
        (throw (error :ansible-vault/empty-password "Invalid vault password was provided (empty)"
                      {:vault-id vault-id}))))
    (mapv (fn [[id pw]] [(str id) pw]) pairs)))

(defn order-secrets
  "Secrets whose vault-id matches `vault-id` first, then the rest, in order."
  [pairs vault-id]
  (let [{matching true others false} (group-by #(= vault-id (first %)) pairs)]
    (vec (concat matching others))))

^:rct/test
(comment
  (normalize-secrets "pw") ;=> [["default" "pw"]]
  (normalize-secrets {"dev" "a"}) ;=> [["dev" "a"]]
  (normalize-secrets [["dev" "a"] ["prod" "b"]]) ;=> [["dev" "a"] ["prod" "b"]]
  (try (normalize-secrets nil) (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))) ;=> :ansible-vault/no-secrets
  (try (normalize-secrets []) (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))) ;=> :ansible-vault/no-secrets
  (try (normalize-secrets "") (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))) ;=> :ansible-vault/empty-password
  (order-secrets [["a" 1] ["dev" 2] ["b" 3] ["dev" 4]] "dev") ;=> [["dev" 2] ["dev" 4] ["a" 1] ["b" 3]]
  (order-secrets [["a" 1]] "dev") ;=> [["a" 1]]
  (order-secrets [] "dev") ;=> []
  )

(defn- try-decrypt
  "Plaintext bytes if `password` passes the HMAC check, else nil."
  [{:keys [salt hmac ciphertext]} password]
  (let [{:keys [key1 key2 iv]} (crypto/derive-keys password salt)]
    (when (crypto/constant-time= hmac (crypto/hmac-sha256 key2 ciphertext))
      (crypto/pkcs7-unpad (crypto/aes-ctr key1 iv ciphertext)))))

(defn decrypt-and-get-vault-id
  "Decrypt vault text (string or bytes) with the given secrets.
   Returns {:plaintext bytes :vault-id id-of-the-secret-that-worked}."
  [vaulttext secrets]
  (let [text (->text vaulttext)]
    (when-not (encrypted? text)
      (throw (error :ansible-vault/not-encrypted "Input is not vault encrypted data")))
    (let [pairs (normalize-secrets secrets)
          {:keys [cipher vault-id body]} (parse-envelope text)]
      (when-not (= cipher-name cipher)
        (throw (error :ansible-vault/unsupported-cipher (str "Cipher " cipher " could not be found")
                      {:cipher cipher})))
      (let [parsed (parse-vaulttext body)]
        (or (some (fn [[id password]]
                    (when-let [plaintext (try-decrypt parsed password)]
                      {:plaintext plaintext :vault-id id}))
                  (order-secrets pairs vault-id))
            (throw (error :ansible-vault/no-matching-secret
                          "Decryption failed (no vault secrets were found that could decrypt)"
                          {:vault-id vault-id
                           :tried (mapv first pairs)})))))))

(defn decrypt-bytes
  "Decrypt vault text (string or bytes); returns the plaintext bytes."
  ^bytes [vaulttext secrets]
  (:plaintext (decrypt-and-get-vault-id vaulttext secrets)))

(defn decrypt
  "Decrypt vault text (string or bytes); returns the plaintext as a UTF-8 string."
  ^String [vaulttext secrets]
  (crypto/utf8-str (decrypt-bytes vaulttext secrets)))

(defn encrypt
  "Encrypt `plaintext` (string, used as UTF-8, or bytes) with `password`.
   Returns the vault text (envelope string, newline terminated).

   opts:
   - :vault-id  label written in the header (format 1.2 unless \"default\")
   - :salt      string or bytes; default 32 random bytes. A fixed salt gives
                deterministic output, like ansible's VAULT_ENCRYPT_SALT."
  ([plaintext password] (encrypt plaintext password {}))
  ([plaintext password {:keys [vault-id salt]}]
   (let [data (if (string? plaintext) (crypto/utf8-bytes plaintext) plaintext)]
     (when-not (bytes? data)
       (throw (IllegalArgumentException. "plaintext must be a string or bytes")))
     (when (encrypted? data)
       (throw (error :ansible-vault/already-encrypted "input is already encrypted")))
     (when (empty? password)
       (throw (error :ansible-vault/empty-password "Invalid vault password was provided (empty)")))
     (let [salt (cond
                  (nil? salt) (crypto/random-salt)
                  (string? salt) (crypto/utf8-bytes salt)
                  :else salt)
           _ (when (zero? (alength ^bytes salt))
               (throw (error :ansible-vault/format-error "Empty or invalid salt passed to encrypt")))
           {:keys [key1 key2 iv]} (crypto/derive-keys password salt)
           ciphertext (crypto/aes-ctr key1 iv (crypto/pkcs7-pad data))
           hmac (crypto/hmac-sha256 key2 ciphertext)
           vaulttext (str (crypto/hexlify salt) "\n"
                          (crypto/hexlify hmac) "\n"
                          (crypto/hexlify ciphertext))]
       (format-envelope (crypto/hexlify (crypto/utf8-bytes vaulttext))
                        cipher-name
                        {:vault-id vault-id})))))

^:rct/test
(comment
  ;; round trip
  (decrypt (encrypt "foo bar\n" "ansible") "ansible") ;=> "foo bar\n"
  (decrypt (encrypt "" "ansible") "ansible") ;=> ""
  (first (str/split-lines (encrypt "x" "pw" {:vault-id "dev"}))) ;=> "$ANSIBLE_VAULT;1.2;AES256;dev"
  (first (str/split-lines (encrypt "x" "pw"))) ;=> "$ANSIBLE_VAULT;1.1;AES256"
  ;; fixed salt is deterministic
  (= (encrypt "x" "pw" {:salt "s"}) (encrypt "x" "pw" {:salt "s"})) ;=> true
  (try (encrypt (encrypt "x" "pw") "pw") (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
  ;=> :ansible-vault/already-encrypted
  (try (decrypt "ansible" "pw") (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
  ;=> :ansible-vault/not-encrypted
  (try (decrypt (encrypt "x" "right") "wrong") (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
  ;=> :ansible-vault/no-matching-secret
  )
