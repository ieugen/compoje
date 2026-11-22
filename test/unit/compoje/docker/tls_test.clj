(ns unit.compoje.docker.tls-test
  "Fixtures in test-resources/docker/tls (test-only, never use elsewhere), made with openssl 3:
     openssl genrsa -traditional -out rsa-pkcs1.pem 2048
     openssl pkcs8 -topk8 -nocrypt -in rsa-pkcs1.pem -out rsa-pkcs8.pem
     openssl ecparam -name prime256v1 -genkey -noout -out ec-sec1.pem
     openssl pkcs8 -topk8 -nocrypt -in ec-sec1.pem -out ec-pkcs8.pem
     openssl ec -in ec-sec1.pem -pubout -out ec-pub.pem
     ca.pem/ca-key.pem: self-signed CA; server-cert.pem/server-key.pem (SAN
     localhost, 127.0.0.1) and client-cert.pem/client-key.pem (EC, SEC1),
     both signed by it."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [compoje.docker.client :as client]
            [compoje.docker.tls :as tls]
            [unit.compoje.docker.fake-server :as fake]))

(def dir "test-resources/docker/tls")
(defn- pem [f] (slurp (str (fs/path dir f))))
(defn- encoded [f] (seq (.getEncoded (tls/read-private-key (pem f)))))

(defn- ec-verifies?
  "Sign with the private key in `f`; verify with ec-pub.pem (the public key
   of ec-sec1.pem). Proves `f` holds that same private key."
  [f]
  (let [data (.getBytes "compoje")
        sig (.sign (doto (java.security.Signature/getInstance "SHA256withECDSA")
                     (.initSign (tls/read-private-key (pem f)))
                     (.update data)))
        pub-der (:der (first (tls/pem-blocks (pem "ec-pub.pem"))))
        pub (.generatePublic (java.security.KeyFactory/getInstance "EC")
                             (java.security.spec.X509EncodedKeySpec. pub-der))]
    (.verify (doto (java.security.Signature/getInstance "SHA256withECDSA")
               (.initVerify pub)
               (.update data))
             sig)))

(deftest private-keys
  (doseq [[f alg] [["rsa-pkcs1.pem" "RSA"] ["rsa-pkcs8.pem" "RSA"]
                   ["ec-sec1.pem" "EC"] ["ec-pkcs8.pem" "EC"]
                   ["client-key.pem" "EC"] ["server-key.pem" "RSA"]]]
    (is (= alg (.getAlgorithm (tls/read-private-key (pem f)))) f))
  (testing "the same key in PKCS#1 and PKCS#8 reads the same"
    (is (= (encoded "rsa-pkcs1.pem") (encoded "rsa-pkcs8.pem"))))
  (testing "the same key in SEC1 and PKCS#8 reads the same"
    (is (ec-verifies? "ec-sec1.pem"))
    (is (ec-verifies? "ec-pkcs8.pem")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No private key"
                        (tls/read-private-key (pem "ca.pem")))))

(deftest certificates
  (is (= ["CN=compoje-test-ca"]
         (map #(str (.getSubjectX500Principal %)) (tls/read-certificates (pem "ca.pem"))))))

(defn- server-context []
  (tls/ssl-context {:ca (pem "ca.pem") :cert (pem "server-cert.pem") :key (pem "server-key.pem")}))

(defn- version-route [_] (fake/response 200 "{\"ApiVersion\":\"1.45\"}"))

(defn- endpoint [port tls]
  {:scheme :tcp :host "localhost" :port port :host-url (str "tcp://localhost:" port)
   :tls (merge {:ca (str (fs/path dir "ca.pem")) :cert (str (fs/path dir "client-cert.pem"))
                :key (str (fs/path dir "client-key.pem")) :verify true}
               tls)})

(def bb? (some? (System/getProperty "babashka.version")))

(defn- mutual-tls* []
  (let [{:keys [port stop]} (fake/start! version-route (server-context))]
    (try
      (testing "client cert + CA verification + hostname check"
        (client/with-connection [conn (endpoint port {})]
          (is (= "1.45" (:api-version conn)))))
      (testing "wrong hostname is refused when verifying"
        (is (thrown? Exception (client/open (assoc (endpoint port {}) :host "127.0.0.2")))))
      (testing "server not signed by the trusted CA is refused"
        (is (thrown? Exception (client/open (endpoint port {:ca (str (fs/path dir "cert.pem"))})))))
      (testing "verify false (SkipTLSVerify) accepts it"
        (client/with-connection [conn (endpoint port {:ca nil :verify false})]
          (is (= "1.45" (:api-version conn)))))
      (testing "no client certificate: the server refuses"
        (is (thrown? Exception (client/open (endpoint port {:cert nil :key nil})))))
      (finally (stop)))))

(deftest mutual-tls
  ;; babashka can't create SSL server sockets, so the in-process TLS server
  ;; runs on the JVM only; the bb client side is checked against openssl
  ;; s_server and, later, a TLS dind in the integration tests.
  (if bb?
    (is true "skipped on babashka")
    (mutual-tls*)))
