;; Copyright Eugen Stan <eugen@ieugen.ro>
(ns compoje.providers.ansible-vault
  "Pure Clojure implementation for ansible vault encrypted file format.
   Should allow us to decrypt and use ansible-vault encrypted files.

   The file format version is documented here:
   - https://docs.ansible.com/ansible/latest/vault_guide/vault_using_encrypted_content.html#format-of-files-encrypted-with-ansible-vault

   We don't plan to implement encryption yet, since we can use ansible-vault for that.

   Other relevant links:
   - https://docs.ansible.com/ansible/latest/cli/ansible-vault.html
   - https://docs.ansible.com/ansible/latest/vault_guide/index.html
   - https://github.com/ansible/ansible/blob/devel/lib/ansible/parsing/vault/__init__.py
   "
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [hyperfiddle.rcf :refer [tests]])
  (:import (java.nio.charset StandardCharsets)
           (java.util Arrays)
           (javax.crypto Mac Cipher SecretKeyFactory SecretKey)
           (javax.crypto.spec PBEKeySpec SecretKeySpec IvParameterSpec)))


(defn parse-header
  "Parse header line for ansible-vault encoded text file.

   Args:
   - line: String containing header

   Result:
   A map with keyword keys for each header value.

   Ansible Vault creates UTF-8 encoded txt files. The file format includes a newline terminated header. For example:
    $ANSIBLE_VAULT;1.1;AES256 or $ANSIBLE_VAULT;1.2;AES256;vault-id-label

   The header contains up to four elements, separated by semi-colons (;).
   - The format ID ($ANSIBLE_VAULT). Currently $ANSIBLE_VAULT is the only valid format ID. The format ID identifies content that is encrypted with Ansible Vault (with vault.is_encrypted_file()).
   - The vault format version (1.X). All supported versions of Ansible will currently default to ‘1.1’ or ‘1.2’ if a labeled vault ID is supplied. The ‘1.0’ format is supported for reading only (and will be converted automatically to the ‘1.1’ format on write). The format version is currently used as an exact string compare only (version numbers are not currently ‘compared’).
   - The cipher algorithm used to encrypt the data (AES256). Currently AES256 is the only supported cipher algorithm. Vault format 1.0 used ‘AES’, but current code always uses ‘AES256’.
   - The vault ID label used to encrypt the data (optional, vault-id-label) For example, if you encrypt a file with --vault-id dev@prompt, the vault-id-label is dev.
   Note: In the future, the header could change. Fields after the format ID and format version depend on the format version, and future vault format versions may add more cipher algorithm options and/or additional fields.

   https://docs.ansible.com/ansible/latest/vault_guide/vault_using_encrypted_content.html#format-of-files-encrypted-with-ansible-vault

   TODO: Check parsed values
   "
  [line]
  (when (str/blank? line)
    (throw (ex-info "Header is null or empty" {})))
  (let [parts (str/split line #";")
        parts-count (count parts)]
    (when (> parts-count 5)
      (throw (ex-info "The header contains up to 4 elements, separated by semi-colons (;)."
                      {:parts parts})))
    (when (< parts-count 3)
      (throw (ex-info "We assume a minimum of 2 parts for header"
                      {:parts parts})))
    (cond-> {:format-id (nth parts 0)
             :format-version (nth parts 1)
             :cipher (nth parts 2)}
      ;; add label if we have it
      (= 4 parts-count)
      (assoc :vault-id-label (nth parts 3)))))

(tests
 "ansible vault parse header"

 (parse-header nil) :throws clojure.lang.ExceptionInfo

 (parse-header "invalid-format") :throws clojure.lang.ExceptionInfo

 (parse-header "$ANSIBLE_VAULT;1.1;AES256") :=
 {:format-id "$ANSIBLE_VAULT", :format-version "1.1", :cipher "AES256"}

 (parse-header "$ANSIBLE_VAULT;1.2;AES256;vault-id-label") :=
 {:format-id "$ANSIBLE_VAULT", :format-version "1.2", :cipher "AES256", :vault-id-label "vault-id-label"}

 )


(defn hexlify
  "Implement python binascii hexlify method used by ansible-vault
   https://docs.python.org/3/library/binascii.html#binascii.hexlify"
  [^bytes data & {:keys [sep] :as _opts
                  :or {sep ""}}]
  ;; TODO: Use character and bit shifting (instead of strings) to improve performance
  (let [b-data data]
    (str/join sep (map #(format "%02X" %) b-data))))

(defn unhexlify
  "Implement python binascii unhexlify method used by ansible-vault
   https://docs.python.org/3/library/binascii.html#binascii.unhexlify"
  [^String hexstr]
  (let [char-count (count hexstr)
        byte-count (/ char-count 2)
        ;; todo: test char count is even !
        byte-data (int-array byte-count)]
    (when-not (even? char-count)
      (throw (ex-info "Illegal state: input has odd number of characters"
                      {:data hexstr})))
    (doseq [i (range 0 byte-count)]
      (let [high (* 2 i)
            low (+ high 1)
            bhigh (-> (Character/digit (.charAt hexstr high) 16)
                      (bit-shift-left 4))
            blow (Character/digit (.charAt hexstr low) 16)
            bv (+ bhigh  blow)]
        ;; (println i " " high " " low " " bv)
        (aset-int byte-data i bv)))
    (byte-array byte-data)))

(tests

 (hexlify (bytes (byte-array [0xb9 0x01 0xef]))) := "B901EF"


 )

(comment

  (let [b-data (bytes (byte-array [0xb9 0x01 0xef]))
        separator ""]
    (str/join separator (map #(format "%02X" %) b-data)))

  (let [hexstr "B901EF"
        char-count (count hexstr)
        byte-count (/ char-count 2)
        ;; todo: test char count is even !
        byte-data (int-array byte-count)]
    (when-not (even? char-count)
      (throw (ex-info "Illegal state: input has odd number of characters"
                      {:data hexstr})))
    (doseq [i (range 0 byte-count)]
      (let [high (* 2 i)
            low (+ high 1)
            bhigh (-> (Character/digit (.charAt hexstr high) 16)
                   (bit-shift-left 4))
            blow (Character/digit (.charAt hexstr low) 16)
            bv (+ bhigh  blow)]
        ;; (println i " " high " " low " " bv)
        (aset-int byte-data i bv)))
    (doseq [b (byte-array byte-data)]
      (println b)))

  (range 0 (count "B901EF") 2)

  (subs "B901EF" 0 2)

  (doseq [b (byte-array [0xb9 0x01 0xef])]
    (println b))

  )


(defn parse-vault-secret
  [value]
  (let [lines (str/split-lines value)
        header (first lines)
        data-lines (map str/trim (rest lines))
        data (str/join "" data-lines)]
    {:header header
     ;;:lines data-lines
     :data data}))

(defn yaml-!vault-tag-handler
  [{:keys [tag value]}]
  (case tag
    "!vault"
    (do
      (println "Decrypt vault")
      value)
    ;; TODO: we should probably throw errors for unknonw tags
    ;; TODO: support user supplied tag parsing ?!
    value))


(defn decrypt
  [algorithm cipher-text key iv]
  (let [cipher (Cipher/getInstance "AES/CTR/PKCS5Padding")]))


(defn create-key-cryptography
  "Generate the full encryption key."
  [password salt key-length iv-length]
  (let [bytes-length (+ (* 2 key-length) iv-length)
        bits-length (* 8 bytes-length)
        key-spec (PBEKeySpec. (.toCharArray password) salt 10000 bits-length)
        key-factory (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
        ^SecretKey pbe-key (.generateSecret key-factory key-spec)
        ^SecretKeySpec secret-key (SecretKeySpec. (.getEncoded pbe-key) "AES")
        key-bytes (.getEncoded secret-key)]
    key-bytes))

(defn hmac
  "Compute hmac values for bytes."
  ;; https://www.baeldung.com/java-hmac#hmac-using-jdk-apis
  (^bytes [^bytes key ^bytes data]
   (hmac key "HmacSHA256" data))
  (^bytes [^bytes key ^String algo ^bytes data]
   (let [^SecretKeySpec key-spec (SecretKeySpec. key algo)
         mac (doto (Mac/getInstance algo)
               (.init key-spec))]
     (.doFinal mac data))))

(defn gen-key-initctr
  "Clojure adaptation of python method form ansible vault"
  ;;
  ;; https://www.baeldung.com/java-secure-aes-key#4-password-based-key
  ;;
  [password salt]
  (let [key-length 32
        ;; AES is a 128-bit block cipher, so IVs and counter nonces are 16 bytes
        iv-length 16
        b-derived-key (create-key-cryptography password salt key-length iv-length)
        _ (println (count b-derived-key))
        b-iv (Arrays/copyOfRange b-derived-key (* 2 key-length) (count b-derived-key))
        b-key1 (Arrays/copyOfRange b-derived-key 0 key-length)
        b-key2 (Arrays/copyOfRange b-derived-key key-length (* 2 key-length))]
    [b-key1 b-key2 b-iv]))


(comment

  (def data "my_encrypted_var: !vault |
          $ANSIBLE_VAULT;1.2;AES256;dev
          30613233633461343837653833666333643061636561303338373661313838333565653635353162
          3263363434623733343538653462613064333634333464660a663633623939393439316636633863
          61636237636537333938306331383339353265363239643939666639386530626330633337633833
          6664656334373166630a363736393262666465663432613932613036303963343263623137386239
          6330")

  (yaml/parse-string data
                     :keywords false
                     :unknown-tag-fn yaml-!vault-tag-handler)



  (let [file-data (parse-vault-secret "$ANSIBLE_VAULT;1.2;AES256;compoje
                  30636333393437623365383966343231613061383131353661386333656231393863646235323566
                  3636336231326131303436363631633130343562623962620a343731666637306466623632636665
                  32376633376439646364313438333633376530656335613566343863666563373364396430306131
                  3634356235306663300a383534653236306333396237663931306239616332643266336538303466
                  30356635303662306161303432396464376664313865343735623930333538353639663831633931
                  6334613831633036383039393762616430613662363835336365")
        crypto-data (String. (unhexlify (:data file-data)) StandardCharsets/UTF_8)
        parts (str/split-lines crypto-data)
        salt (unhexlify (nth parts 0))
        b-crypted-hmac (unhexlify (nth parts 1))
        b-ciphertext (unhexlify (nth parts 2))
        pass "s€cure"
        ;; https://github.com/ansible/ansible/blob/f8de6caeec735fad53c2fa492c94608e92ebfb06/lib/ansible/parsing/vault/__init__.py#L1140C19-L1140C19
        [b-key1 b-key2 b-iv] (gen-key-initctr pass salt)
        b-hmac (hmac b-key2 b-ciphertext)
        secret-key (SecretKeySpec. b-key1 "AES")
        iv-spec (IvParameterSpec. b-iv)
        cipher (doto (Cipher/getInstance "AES/CTR/NoPadding")
                 (.init Cipher/DECRYPT_MODE secret-key iv-spec))
        data (.doFinal cipher b-ciphertext)]
    (println "aaa"
             (count b-key1)
             (count b-key2)
             (count b-iv)
             (Arrays/equals b-hmac b-crypted-hmac))
    (println "Decrypted" (String. data StandardCharsets/UTF_8)))


  )