(ns unit.compoje.docker.endpoint-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [compoje.docker.endpoint :as e]))

(defn- docker-home
  "Temp home with a ~/.docker tree: `contexts` is {name {:host .. :skip-tls ..
   :tls? bool}}, `current` the currentContext."
  [{:keys [contexts current]}]
  (let [home (str (fs/create-temp-dir {:prefix "compoje-home-"}))
        cfg (fs/path home ".docker")]
    (fs/create-dirs cfg)
    (spit (str (fs/path cfg "config.json"))
          (json/generate-string (cond-> {} current (assoc "currentContext" current))))
    (doseq [[n {:keys [host skip-tls tls?]}] contexts
            :let [d (e/context-dir-name n)
                  meta-dir (fs/path cfg "contexts" "meta" d)]]
      (fs/create-dirs meta-dir)
      (spit (str (fs/path meta-dir "meta.json"))
            (json/generate-string {"Name" n "Endpoints" {"docker" {"Host" host "SkipTLSVerify" (boolean skip-tls)}}}))
      (when tls?
        (let [tls-dir (fs/path cfg "contexts" "tls" d "docker")]
          (fs/create-dirs tls-dir)
          (doseq [f ["ca.pem" "cert.pem" "key.pem"]] (spit (str (fs/path tls-dir f)) "pem")))))
    home))

(def contexts
  {"remote" {:host "ssh://ubuntu@swarm.example.com:2122"}
   "tls" {:host "tcp://10.0.0.5:2376" :tls? true}
   "tls-skip" {:host "tcp://10.0.0.6" :tls? true :skip-tls true}})

(deftest resolution-order
  (let [home (docker-home {:contexts contexts :current "remote"})]
    (testing "explicit context wins over everything"
      (is (= {:scheme :tcp :host "10.0.0.5" :port 2376 :context "tls" :source :flag}
             (select-keys (e/resolve-endpoint {:context "tls" :home home :env {"DOCKER_HOST" "unix:///x"}})
                          [:scheme :host :port :context :source]))))
    (testing "DOCKER_HOST before DOCKER_CONTEXT and currentContext"
      (is (= {:scheme :unix :path "/run/d.sock" :source :DOCKER_HOST}
             (select-keys (e/resolve-endpoint {:home home :env {"DOCKER_HOST" "unix:///run/d.sock"
                                                                "DOCKER_CONTEXT" "tls"}})
                          [:scheme :path :source]))))
    (testing "DOCKER_CONTEXT before currentContext"
      (is (= :DOCKER_CONTEXT (:source (e/resolve-endpoint {:home home :env {"DOCKER_CONTEXT" "tls"}})))))
    (testing "currentContext from config.json"
      (is (= {:scheme :ssh :host "swarm.example.com" :user "ubuntu" :port 2122 :context "remote" :source :config}
             (select-keys (e/resolve-endpoint {:home home :env {}})
                          [:scheme :host :user :port :context :source]))))
    (testing "the context named default means DOCKER_HOST or the local socket"
      (is (= "/var/run/docker.sock" (:path (e/resolve-endpoint {:context "default" :home home :env {}})))))))

(deftest default-endpoint
  (let [home (docker-home {})]
    (is (= {:scheme :unix :path "/var/run/docker.sock" :source :default}
           (select-keys (e/resolve-endpoint {:home home :env {}}) [:scheme :path :source])))))

(deftest context-tls
  (let [home (docker-home {:contexts contexts})
        tls-dir (str (fs/path home ".docker" "contexts" "tls" (e/context-dir-name "tls") "docker"))]
    (testing "a tcp context with TLS files verifies by default"
      (is (= {:ca (str (fs/path tls-dir "ca.pem")) :cert (str (fs/path tls-dir "cert.pem"))
              :key (str (fs/path tls-dir "key.pem")) :verify true}
             (:tls (e/resolve-endpoint {:context "tls" :home home :env {}})))))
    (testing "SkipTLSVerify, and port 2376 when TLS is used without a port"
      (let [ep (e/resolve-endpoint {:context "tls-skip" :home home :env {}})]
        (is (false? (get-in ep [:tls :verify])))
        (is (= 2376 (:port ep)))))
    (testing "ssh contexts never get TLS"
      (is (nil? (:tls (e/resolve-endpoint {:context "remote" :home home :env {}})))))))

(deftest env-tls
  (let [home (docker-home {})
        certs (str (fs/create-temp-dir))]
    (doseq [f ["ca.pem" "cert.pem" "key.pem"]] (spit (str (fs/path certs f)) "pem"))
    (testing "DOCKER_TLS_VERIFY + DOCKER_CERT_PATH for a tcp DOCKER_HOST"
      (let [ep (e/resolve-endpoint {:home home :env {"DOCKER_HOST" "tcp://h" "DOCKER_TLS_VERIFY" "1"
                                                     "DOCKER_CERT_PATH" certs}})]
        (is (= 2376 (:port ep)))
        (is (true? (get-in ep [:tls :verify])))
        (is (= (str (fs/path certs "ca.pem")) (get-in ep [:tls :ca])))))
    (testing "plain tcp DOCKER_HOST has no TLS and port 2375"
      (let [ep (e/resolve-endpoint {:home home :env {"DOCKER_HOST" "tcp://h"}})]
        (is (nil? (:tls ep)))
        (is (= 2375 (:port ep)))))))

(deftest docker-config-env
  (let [home (docker-home {:contexts contexts :current "remote"})
        other (str (fs/path home ".docker"))]
    (is (= "remote" (:context (e/resolve-endpoint {:home "/nonexistent" :env {"DOCKER_CONFIG" other}}))))))

(deftest errors
  (let [home (docker-home {:contexts contexts})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Docker context not found: nope"
                          (e/resolve-endpoint {:context "nope" :home home :env {}})))))
