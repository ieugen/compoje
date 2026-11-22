(ns compoje.docker.tls
  "SSLContext for mutual TLS to a Docker daemon (tcp://host:2376), from the
   PEM files docker uses: ca.pem, cert.pem, key.pem.

   Private keys may be PKCS#8 (BEGIN PRIVATE KEY), PKCS#1 RSA (BEGIN RSA
   PRIVATE KEY) or SEC1 EC (BEGIN EC PRIVATE KEY). PKCS#1 and SEC1 are wrapped
   into PKCS#8 with a few DER helpers, so no BouncyCastle is needed (bb)."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayInputStream)
           (java.security KeyFactory KeyStore)
           (java.security.cert CertificateFactory X509Certificate)
           (java.security.spec PKCS8EncodedKeySpec)
           (java.util Base64)
           (javax.net.ssl KeyManagerFactory SSLContext TrustManagerFactory X509TrustManager)))

;; ------------------------------------------------------------------ PEM

(defn pem-blocks
  "[{:type \"RSA PRIVATE KEY\" :der bytes} ...] for every PEM block in `text`."
  [text]
  (for [[_ type body] (re-seq #"(?s)-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \1-----" text)]
    {:type type
     :der (.decode (Base64/getMimeDecoder) ^String (str/trim body))}))

^:rct/test
(comment
  (map (juxt :type #(vec (:der %)))
       (pem-blocks "x\n-----BEGIN TEST-----\nAQID\n-----END TEST-----\n-----BEGIN B-----\nBA==\n-----END B-----\n"))
  ;=> [["TEST" [1 2 3]] ["B" [4]]]
  )

;; ------------------------------------------------------------------ DER

(defn- der-length-bytes [n]
  (if (< n 128)
    [n]
    (let [bs (loop [n n acc ()] (if (zero? n) acc (recur (bit-shift-right n 8) (conj acc (bit-and n 0xff)))))]
      (into [(bit-or 0x80 (count bs))] bs))))

(defn der
  "DER TLV: tag byte + length + content bytes."
  ^bytes [tag content]
  (let [content (vec (map #(bit-and % 0xff) content))]
    (byte-array (map unchecked-byte (concat [tag] (der-length-bytes (count content)) content)))))

^:rct/test
(comment
  (vec (der 0x04 [1 2])) ;=> [4 2 1 2]
  (vec (take 4 (der 0x04 (repeat 200 0)))) ;=> [4 -127 -56 0]
  )

(defn- der-seq ^bytes [& parts] (der 0x30 (mapcat seq parts)))

(defn read-tlv
  "Read one TLV at `off` of `bs`: {:tag :start (content offset) :len :end}."
  [^bytes bs off]
  (let [u #(bit-and (aget bs %) 0xff)
        tag (u off)
        l0 (u (inc off))
        [len start] (if (< l0 128)
                      [l0 (+ off 2)]
                      (let [n (bit-and l0 0x7f)]
                        [(reduce (fn [acc i] (+ (* acc 256) (u (+ off 2 i)))) 0 (range n))
                         (+ off 2 n)]))]
    {:tag tag :start start :len len :end (+ start len)}))

(defn- tlv-children
  "Child TLVs of the constructed TLV `parent`."
  [^bytes bs {:keys [start end]}]
  (loop [off start acc []]
    (if (>= off end) acc (let [t (read-tlv bs off)] (recur (:end t) (conj acc t))))))

(defn- tlv-bytes ^bytes [^bytes bs {:keys [start end]}]
  (java.util.Arrays/copyOfRange bs (int start) (int end)))

(def ^:private oid-rsa-encryption [0x06 0x09 0x2a 0x86 0x48 0x86 0xf7 0x0d 0x01 0x01 0x01])
(def ^:private oid-ec-public-key [0x06 0x07 0x2a 0x86 0x48 0xce 0x3d 0x02 0x01])
(def ^:private der-null [0x05 0x00])
(def ^:private der-int-0 [0x02 0x01 0x00])

(defn pkcs1->pkcs8
  "Wrap a PKCS#1 RSAPrivateKey in a PKCS#8 PrivateKeyInfo."
  ^bytes [^bytes pkcs1]
  (der-seq (byte-array (map unchecked-byte der-int-0))
           (der-seq (byte-array (map unchecked-byte (concat oid-rsa-encryption der-null))))
           (der 0x04 pkcs1)))

(defn sec1->pkcs8
  "Wrap a SEC1 ECPrivateKey (which names its curve in [0] parameters) in a
   PKCS#8 PrivateKeyInfo."
  ^bytes [^bytes sec1]
  (let [top (read-tlv sec1 0)
        params (first (filter #(= 0xa0 (:tag %)) (tlv-children sec1 top)))
        _ (when-not params
            (throw (ex-info "EC private key without curve parameters is not supported" {})))
        curve-oid (tlv-bytes sec1 (assoc params :start (:start params) :end (:end params)))]
    (der-seq (byte-array (map unchecked-byte der-int-0))
             (der-seq (byte-array (map unchecked-byte oid-ec-public-key)) curve-oid)
             (der 0x04 sec1))))

;; ------------------------------------------------------------------ keys and certs

(defn read-private-key
  "PrivateKey from PEM text (PKCS#8, PKCS#1 RSA or SEC1 EC)."
  [pem]
  (let [{:keys [type der]} (or (first (filter #(str/ends-with? (:type %) "PRIVATE KEY") (pem-blocks pem)))
                               (throw (ex-info "No private key in PEM" {})))
        [pkcs8 algs] (case type
                       "PRIVATE KEY" [der ["RSA" "EC" "Ed25519"]]
                       "RSA PRIVATE KEY" [(pkcs1->pkcs8 der) ["RSA"]]
                       "EC PRIVATE KEY" [(sec1->pkcs8 der) ["EC"]]
                       (throw (ex-info (str "Unsupported private key type: " type
                                            " (encrypted keys are not supported)")
                                       {:type type})))]
    (or (some (fn [alg]
                (try (.generatePrivate (KeyFactory/getInstance alg) (PKCS8EncodedKeySpec. pkcs8))
                     (catch Exception _ nil)))
              algs)
        (throw (ex-info (str "Could not read the " type) {:type type})))))

(defn read-certificates
  "X509Certificates from PEM text."
  [pem]
  (vec (.generateCertificates (CertificateFactory/getInstance "X.509")
                              (ByteArrayInputStream. (.getBytes ^String pem "US-ASCII")))))

(def trust-all
  "Trust manager that accepts any server certificate (SkipTLSVerify)."
  (reify X509TrustManager
    (checkClientTrusted [_ _ _])
    (checkServerTrusted [_ _ _])
    (getAcceptedIssuers [_] (make-array X509Certificate 0))))

(defn ssl-context
  "SSLContext from PEM strings. `ca` (trusted CAs) is required unless
   `verify` is false; `cert` + `key` give the client certificate."
  [{:keys [ca cert key verify] :or {verify true}}]
  (let [password (char-array 0)
        key-managers (when (and cert key)
                       (let [ks (doto (KeyStore/getInstance "PKCS12") (.load nil nil))]
                         (.setKeyEntry ks "client" (read-private-key key) password
                                       (into-array X509Certificate (read-certificates cert)))
                         (.getKeyManagers (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
                                            (.init ks password)))))
        trust-managers (cond
                         ;; bb can't build a TrustManager[] reflectively: reuse a real one
                         (not verify) (let [tms (.getTrustManagers
                                                 (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                                                   (.init (doto (KeyStore/getInstance "PKCS12") (.load nil nil)))))]
                                        (aset tms 0 trust-all)
                                        tms)
                         ca (let [ts (doto (KeyStore/getInstance "PKCS12") (.load nil nil))]
                              (doseq [[i c] (map-indexed vector (read-certificates ca))]
                                (.setCertificateEntry ts (str "ca" i) c))
                              (.getTrustManagers (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                                                   (.init ts))))
                         :else nil)]
    (doto (SSLContext/getInstance "TLS")
      (.init key-managers trust-managers nil))))

