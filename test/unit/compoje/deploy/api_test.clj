(ns unit.compoje.deploy.api-test
  "compoje.deploy.api against a fake Docker daemon (unit.compoje.docker.fake-server)."
  (:require [babashka.fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [compoje.deploy.api :as api]
            [compoje.deploy.swarm-convert :as c]
            [unit.compoje.docker.fake-server :as fake]))

(def stack
  (c/parse-stack (str "services:\n  web:\n    image: docker.io/nginx:stable\n"
                      "    secrets: [pw]\n    configs: [conf]\n"
                      "secrets:\n  pw:\n    external: true\n    name: web_pw-abc\n"
                      "configs:\n  conf:\n    file: app.conf\n")))

(defn- routes [{:keys [request-line]}]
  (condp re-find request-line
    #"GET /version " (fake/response 200 "{\"ApiVersion\":\"1.47\"}")
    #"GET /v1.47/info " (fake/response 200 "{\"Swarm\":{\"ControlAvailable\":true}}")
    #"GET /v1.47/networks\?" (fake/response 200 "[]")
    #"POST /v1.47/networks/create" (fake/response 201 "{\"Id\":\"n1\"}")
    #"GET /v1.47/secrets\?" (fake/response 200 "[]")
    #"POST /v1.47/secrets/create" (fake/response 201 "{\"ID\":\"s-new\"}")
    #"GET /v1.47/configs\?" (fake/response 200 "[]")
    #"POST /v1.47/configs/create" (fake/response 201 "{\"ID\":\"c-new\"}")
    #"GET /v1.47/services\?" (fake/response 200 "[]")
    #"POST /v1.47/services/create" (fake/response 201 "{\"ID\":\"svc1\"}")
    (fake/response 500 (json/generate-string {"message" (str "unexpected " request-line)}))))

(defn- run [opts]
  (let [{:keys [port requests stop]} (fake/start! routes)
        dir (str (babashka.fs/create-temp-dir))]
    (spit (str (babashka.fs/path dir "app.conf")) "listen 80;")
    (try
      (api/deploy! stack (merge {:endpoint {:scheme :tcp :host "127.0.0.1" :port port
                                            :allow-insecure-tcp true :host-url "tcp://fake"}
                                 :docker {:stack "web" :resolve-image "never" :with-registry-auth false}
                                 :objects [{:kind :secret :key "pw" :name "web_pw-abc" :data "s3cret"
                                            :labels {"com.docker.stack.namespace" "web"}}]
                                 :template-dir dir
                                 :docker-config {}}
                                opts))
      @requests
      (finally (stop)))))

(defn- body [req] (json/parse-string (:body req)))
(defn- posts [reqs] (filter #(str/starts-with? (:request-line %) "POST") reqs))

(deftest creates-everything
  (let [reqs (run {})
        by-path (fn [re] (first (filter #(re-find re (:request-line %)) reqs)))]
    (is (= ["POST /v1.47/networks/create" "POST /v1.47/secrets/create"
            "POST /v1.47/configs/create" "POST /v1.47/services/create"]
           (map #(first (str/split (:request-line %) #" HTTP")) (posts reqs))))
    (testing "managed secret created from memory, base64"
      (is (= {"Name" "web_pw-abc" "Data" "czNjcmV0" "Labels" {"com.docker.stack.namespace" "web"}}
             (body (by-path #"POST /v1.47/secrets/create")))))
    (testing "file config read and labelled"
      (is (= {"Name" "web_conf" "Data" "bGlzdGVuIDgwOw==" "Labels" {"com.docker.stack.namespace" "web"}}
             (body (by-path #"POST /v1.47/configs/create")))))
    (testing "service references the created object IDs"
      (let [cs (get-in (body (by-path #"POST /v1.47/services/create")) ["TaskTemplate" "ContainerSpec"])]
        (is (= "nginx:stable" (get cs "Image")))
        (is (= [{"File" {"Name" "pw" "UID" "0" "GID" "0" "Mode" 292} "SecretID" "s-new" "SecretName" "web_pw-abc"}]
               (get cs "Secrets")))
        (is (= "c-new" (get-in cs ["Configs" 0 "ConfigID"])))))))

(deftest dry-run-only-reads
  (is (empty? (posts (run {:dry-run true})))))
