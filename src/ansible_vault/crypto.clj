(ns ansible-vault.crypto
  "Cryptographic primitives for the ansible-vault format (AES256 cipher).

   Uses only the JDK and clojure.core, so it runs on the JVM and in Babashka.
   Mirrors VaultAES256 in ansible-core lib/ansible/parsing/vault/__init__.py:
   - keys: PBKDF2-HMAC-SHA256, 10000 iterations, 80 bytes -> key1 | key2 | iv
   - cipher: AES-256-CTR with key1, plaintext padded with PKCS7 (128-bit block)
   - integrity: HMAC-SHA256 over the ciphertext with key2"
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Arrays)
           (javax.crypto Cipher Mac SecretKeyFactory)
           (javax.crypto.spec IvParameterSpec PBEKeySpec SecretKeySpec)))

(defn format-error
  "ex-info for malformed vault data."
  ([msg] (format-error msg {}))
  ([msg data]
   (ex-info msg (assoc data :type :ansible-vault/format-error))))

;; ---------------------------------------------------------------- hex

(def ^:private hex-chars "0123456789abcdef")

(defn hexlify
  "Bytes -> lowercase hex string, like python binascii.hexlify."
  ^String [^bytes data]
  (let [sb (StringBuilder. (* 2 (alength data)))]
    (doseq [b data]
      (let [v (bit-and (int b) 0xff)]
        (.append sb (.charAt ^String hex-chars (bit-shift-right v 4)))
        (.append sb (.charAt ^String hex-chars (bit-and v 0x0f)))))
    (.toString sb)))

(defn unhexlify
  "Hex string (either case) -> bytes, like python binascii.unhexlify.
   Throws :ansible-vault/format-error on odd length or non-hex characters."
  ^bytes [^String hex]
  (let [n (count hex)]
    (when (odd? n)
      (throw (format-error "Vault format unhexlify error: odd-length string"
                           {:length n})))
    (let [out (byte-array (quot n 2))]
      (dotimes [i (quot n 2)]
        (let [hi (Character/digit (.charAt hex (* 2 i)) 16)
              lo (Character/digit (.charAt hex (inc (* 2 i))) 16)]
          (when (or (neg? hi) (neg? lo))
            (throw (format-error "Vault format unhexlify error: Non-hexadecimal digit found"
                                 {:index (* 2 i)})))
          (aset out i (unchecked-byte (+ (* 16 hi) lo)))))
      out)))

^:rct/test
(comment
  (hexlify (byte-array [0xb9 0x01 0xef])) ;=> "b901ef"
  (hexlify (byte-array 0)) ;=> ""
  (vec (unhexlify "B901ef")) ;=> [-71 1 -17]
  (String. (unhexlify (hexlify (.getBytes "some text to hexlify"))) "UTF-8") ;=> "some text to hexlify"
  (try (unhexlify "abc") (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
  ;=> :ansible-vault/format-error
  (try (unhexlify "6z") (catch clojure.lang.ExceptionInfo e (ex-message e)))
  ;=> "Vault format unhexlify error: Non-hexadecimal digit found"
  )

;; ---------------------------------------------------------------- PKCS7

(def block-size
  "AES block size in bytes; ansible pads with PKCS7(128)."
  16)

(defn pkcs7-pad
  "Pad to a multiple of `block-size` (default 16) bytes with N bytes of value N.
   Always adds padding: a full block when the input is already aligned."
  (^bytes [^bytes data] (pkcs7-pad block-size data))
  (^bytes [size ^bytes data]
   (let [len (alength data)
         n (- size (mod len size))
         out (Arrays/copyOf data (int (+ len n)))]
     (Arrays/fill out (int len) (int (+ len n)) (unchecked-byte n))
     out)))

(defn pkcs7-unpad
  "Remove PKCS7 padding. Throws :ansible-vault/format-error if the input is not
   a non-empty multiple of `block-size` or the padding bytes are invalid."
  (^bytes [^bytes data] (pkcs7-unpad block-size data))
  (^bytes [size ^bytes data]
   (let [len (alength data)]
     (when (or (zero? len) (pos? (mod len size)))
       (throw (format-error "Invalid padding: length is not a positive multiple of the block size"
                            {:length len :block-size size})))
     (let [n (bit-and (int (aget data (dec len))) 0xff)]
       (when-not (and (<= 1 n size)
                      (every? #(= n (bit-and (int (aget data %)) 0xff))
                              (range (- len n) len)))
         (throw (format-error "Invalid padding bytes" {})))
       (Arrays/copyOfRange data 0 (int (- len n)))))))

^:rct/test
(comment
  (vec (pkcs7-pad (byte-array 0))) ;=> [16 16 16 16 16 16 16 16 16 16 16 16 16 16 16 16]
  (count (pkcs7-pad (byte-array 15))) ;=> 16
  (last (pkcs7-pad (byte-array 15))) ;=> 1
  (count (pkcs7-pad (byte-array 16))) ;=> 32
  (count (pkcs7-pad (byte-array 17))) ;=> 32
  (last (pkcs7-pad (byte-array 17))) ;=> 15
  (vec (pkcs7-unpad (pkcs7-pad (byte-array [1 2 3])))) ;=> [1 2 3]
  (count (pkcs7-unpad (pkcs7-pad (byte-array 16)))) ;=> 16
  (count (pkcs7-unpad (pkcs7-pad (byte-array 0)))) ;=> 0
  (try (pkcs7-unpad (byte-array 15)) (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
  ;=> :ansible-vault/format-error
  (try (pkcs7-unpad (byte-array 16)) (catch clojure.lang.ExceptionInfo e (ex-message e)))
  ;=> "Invalid padding bytes"
  (try (pkcs7-unpad (byte-array (concat (repeat 14 0) [1 2]))) (catch clojure.lang.ExceptionInfo e (ex-message e)))
  ;=> "Invalid padding bytes"
  )

;; ---------------------------------------------------------------- keys, cipher, mac

(def ^:private key-length 32)
(def ^:private iv-length 16)
(def ^:private pbkdf2-iterations 10000)

(defn derive-keys
  "Derive {:key1 :key2 :iv} (byte arrays) from a password string and salt bytes.
   The password is used as UTF-8, like ansible's VaultSecret.bytes."
  [^String password ^bytes salt]
  (let [spec (PBEKeySpec. (.toCharArray password) salt pbkdf2-iterations
                          (* 8 (+ (* 2 key-length) iv-length)))
        derived (.getEncoded (.generateSecret (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256") spec))]
    {:key1 (Arrays/copyOfRange derived 0 key-length)
     :key2 (Arrays/copyOfRange derived key-length (* 2 key-length))
     :iv (Arrays/copyOfRange derived (* 2 key-length) (+ (* 2 key-length) iv-length))}))

^:rct/test
(comment
  ;; known vector, computed with python cryptography (used by ansible-core)
  (let [{:keys [key1 key2 iv]} (derive-keys "hunter42" (.getBytes (apply str (repeat 32 "q")) "UTF-8"))]
    [(hexlify key1) (hexlify key2) (hexlify iv)])
  ;=> ["6509232fa5db2b9c316c8abc9a9b17c5417fcbc645535b46d76223d672ada0a7"
  ;    "6a337bf9296572ec149a59269090198194e4a48f2bf1ce67a7869412598759cb"
  ;    "011120301b316076039325e8c100738b"]

  ;; non-ASCII password is encoded as UTF-8
  (hexlify (:iv (derive-keys "s€cure" (.getBytes (apply str (repeat 32 "q")) "UTF-8"))))
  ;=> "974382c3a4617b4325318f07c1d273cd"
  )

(defn aes-ctr
  "AES/CTR transform (encryption and decryption are the same operation)."
  ^bytes [^bytes key ^bytes iv ^bytes data]
  (let [cipher (doto (Cipher/getInstance "AES/CTR/NoPadding")
                 (.init Cipher/ENCRYPT_MODE (SecretKeySpec. key "AES") (IvParameterSpec. iv)))]
    (.doFinal cipher data)))

(defn hmac-sha256
  ^bytes [^bytes key ^bytes data]
  (let [mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. key "HmacSHA256")))]
    (.doFinal mac data)))

^:rct/test
(comment
  (let [k (byte-array 32) iv (byte-array 16) data (.getBytes "ansible" "UTF-8")]
    (String. (aes-ctr k iv (aes-ctr k iv data)) "UTF-8")) ;=> "ansible"
  ;; RFC 4231 test case 2
  (hexlify (hmac-sha256 (.getBytes "Jefe" "UTF-8") (.getBytes "what do ya want for nothing?" "UTF-8")))
  ;=> "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
  )

(defn constant-time=
  "Compare two byte arrays in constant time. Throws IllegalArgumentException
   unless both arguments are byte arrays (like ansible's _is_equal TypeError)."
  [a b]
  (when-not (and (bytes? a) (bytes? b))
    (throw (IllegalArgumentException. "constant-time= can only compare two byte arrays")))
  (MessageDigest/isEqual ^bytes a ^bytes b))

^:rct/test
(comment
  (constant-time= (.getBytes "abcdefghijklmnopqrstuvwxyz") (.getBytes "abcdefghijklmnopqrstuvwxyz")) ;=> true
  (constant-time= (.getBytes "abcdefghijklmnopqrstuvwxyz") (.getBytes "abcdefghijklmnopqrstuvwx and sometimes y")) ;=> false
  (constant-time= (.getBytes "abcdefghijklmnopqrstuvwxyz") (.getBytes "AbcdefghijKlmnopQrstuvwxZ")) ;=> false
  (constant-time= (byte-array 0) (byte-array 0)) ;=> true
  (let [jp (.getBytes "私はガラスを食べられます。それは私を傷つけません。" "UTF-8")]
    [(constant-time= jp jp)
     (constant-time= jp (.getBytes "Pot să mănânc sticlă și ea nu mă rănește." "UTF-8"))
     (constant-time= jp (doto (aclone jp) (aset (dec (alength jp)) (byte 80))))])
  ;=> [true false false]
  (try (constant-time= "One fish" (.getBytes "two fish")) (catch IllegalArgumentException _ :type-error)) ;=> :type-error
  (try (constant-time= (.getBytes "blue fish") 2) (catch IllegalArgumentException _ :type-error)) ;=> :type-error
  )

(defn random-salt
  "32 random bytes from SecureRandom (side effect)."
  ^bytes []
  (let [salt (byte-array 32)]
    (.nextBytes (SecureRandom.) salt)
    salt))

(defn utf8-bytes ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))
(defn utf8-str ^String [^bytes b] (String. b StandardCharsets/UTF_8))
