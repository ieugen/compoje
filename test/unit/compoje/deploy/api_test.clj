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

(defn- base-route [request-line]
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

(defn- routes
  "Fake daemon. `extra`: request-line regex -> response, tried first."
  [extra {:keys [request-line] :as req}]
  (or (some (fn [[re resp]] (when (re-find re request-line) (if (fn? resp) (resp req) resp))) extra)
      (base-route request-line)))

(defn- run
  "Deploy `stack` against the fake daemon; returns the requests it received.
   `opts` are merged into deploy!'s options (:docker into the docker map)."
  ([opts] (run opts nil))
  ([opts extra]
   (let [{:keys [port requests stop]} (fake/start! (partial routes extra))
         dir (str (babashka.fs/create-temp-dir))]
     (spit (str (babashka.fs/path dir "app.conf")) "listen 80;")
     (try
       (api/deploy! stack (merge {:endpoint {:scheme :tcp :host "127.0.0.1" :port port
                                             :allow-insecure-tcp true :host-url "tcp://fake"}
                                  :objects [{:kind :secret :key "pw" :name "web_pw-abc" :data "s3cret"
                                             :labels {"com.docker.stack.namespace" "web"}}]
                                  :template-dir dir
                                  :docker-config {}}
                                 (dissoc opts :docker)
                                 {:docker (merge {:stack "web" :resolve-image "never" :with-registry-auth false}
                                                 (:docker opts))}))
       @requests
       (finally (stop))))))

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

;; ------------------------------------------------------------------ deploy options

(def distribution
  "Registry answer for nginx:stable: a digest and its platforms (with an
   attestation manifest and an armv7 entry, which docker filters/clears)."
  (fake/response 200 (json/generate-string
                      {"Descriptor" {"digest" "sha256:new"}
                       "Platforms" [{"architecture" "amd64" "os" "linux"}
                                    {"architecture" "arm" "os" "linux" "variant" "v7"}
                                    {"architecture" "unknown" "os" "unknown"}]})))

(def old-service
  "An existing web_web service, pinned by an earlier deploy of `stack`."
  {"ID" "svc-old" "Version" {"Index" 7}
   "Spec" {"Name" "web_web"
           "Labels" {"com.docker.stack.namespace" "web" "com.docker.stack.image" "docker.io/nginx:stable"}
           "TaskTemplate" {"ContainerSpec" {"Image" "nginx:stable@sha256:old"}
                           "Placement" {"Platforms" [{"OS" "linux" "Architecture" "arm64"}]}
                           "ForceUpdate" 3}}})

(defn- services-route [& services] (fake/response 200 (json/generate-string (vec services))))

(defn- request [reqs re] (first (filter #(re-find re (:request-line %)) reqs)))
(defn- distribution-requests [reqs] (filter #(re-find #"GET /v1.47/distribution/" (:request-line %)) reqs))
(defn- created-spec [reqs] (body (request reqs #"POST /v1.47/services/create")))
(defn- updated [reqs] (request reqs #"POST /v1.47/services/svc-old/update"))

(deftest resolve-image-always
  (testing "pins the image to the registry digest and records its platforms (default)"
    (let [reqs (run {:docker {:resolve-image "always"}} {#"GET /v1.47/distribution/" distribution})
          spec (created-spec reqs)]
      (is (= ["GET /v1.47/distribution/nginx:stable/json HTTP/1.1"]
             (map :request-line (distribution-requests reqs))))
      (is (= "nginx:stable@sha256:new" (get-in spec ["TaskTemplate" "ContainerSpec" "Image"])))
      (is (= [{"OS" "linux" "Architecture" "amd64"} {"OS" "linux"}]
             (get-in spec ["TaskTemplate" "Placement" "Platforms"])))))
  (testing "registry not reachable: deploys the tag unpinned"
    (let [reqs (run {:docker {:resolve-image "always"}}
                    {#"GET /v1.47/distribution/" (fake/response 401 "{\"message\":\"unauthorized\"}")})
          spec (created-spec reqs)]
      (is (= "nginx:stable" (get-in spec ["TaskTemplate" "ContainerSpec" "Image"])))
      (is (nil? (get-in spec ["TaskTemplate" "Placement" "Platforms"])))))
  (testing "existing service: re-resolved even when the tag is the same; ForceUpdate kept"
    (let [reqs (run {:docker {:resolve-image "always"}}
                    {#"GET /v1.47/distribution/" distribution
                     #"GET /v1.47/services\?" (services-route old-service)
                     #"POST /v1.47/services/svc-old/update" (fake/response 200 "{}")})
          upd (updated reqs)]
      (is (= 1 (count (distribution-requests reqs))))
      (is (str/includes? (:request-line upd) "version=7"))
      (is (= "nginx:stable@sha256:new" (get-in (body upd) ["TaskTemplate" "ContainerSpec" "Image"])))
      (is (= 3 (get-in (body upd) ["TaskTemplate" "ForceUpdate"]))))))

(deftest resolve-image-changed
  (testing "same tag as deployed: keeps the pinned image and platforms, no registry query"
    (let [reqs (run {:docker {:resolve-image "changed"}}
                    {#"GET /v1.47/distribution/" distribution
                     #"GET /v1.47/services\?" (services-route old-service)
                     #"POST /v1.47/services/svc-old/update" (fake/response 200 "{}")})
          cs (body (updated reqs))]
      (is (empty? (distribution-requests reqs)))
      (is (= "nginx:stable@sha256:old" (get-in cs ["TaskTemplate" "ContainerSpec" "Image"])))
      (is (= [{"OS" "linux" "Architecture" "arm64"}] (get-in cs ["TaskTemplate" "Placement" "Platforms"])))))
  (testing "tag changed: resolves the new one"
    (let [old (assoc-in old-service ["Spec" "Labels" "com.docker.stack.image"] "docker.io/nginx:mainline")
          reqs (run {:docker {:resolve-image "changed"}}
                    {#"GET /v1.47/distribution/" distribution
                     #"GET /v1.47/services\?" (services-route old)
                     #"POST /v1.47/services/svc-old/update" (fake/response 200 "{}")})]
      (is (= 1 (count (distribution-requests reqs))))
      (is (= "nginx:stable@sha256:new"
             (get-in (body (updated reqs)) ["TaskTemplate" "ContainerSpec" "Image"])))))
  (testing "new service: resolved"
    (let [reqs (run {:docker {:resolve-image "changed"}} {#"GET /v1.47/distribution/" distribution})]
      (is (= "nginx:stable@sha256:new"
             (get-in (created-spec reqs) ["TaskTemplate" "ContainerSpec" "Image"]))))))

(deftest resolve-image-never
  (let [reqs (run {:docker {:resolve-image "never"}} {#"GET /v1.47/distribution/" distribution})]
    (is (empty? (distribution-requests reqs)))
    (is (= "nginx:stable" (get-in (created-spec reqs) ["TaskTemplate" "ContainerSpec" "Image"])))))

(def docker-config
  "config.json with inline credentials for Docker Hub (u:p)."
  {"auths" {"https://index.docker.io/v1/" {"auth" "dTpw"}}})

(defn- auth-json [req]
  (some-> (get-in req [:headers "x-registry-auth"])
          (->> (.decode (java.util.Base64/getUrlDecoder)) (String.))
          json/parse-string))

(deftest with-registry-auth
  (testing "on: credentials from config.json go to the registry query and the service create"
    (let [reqs (run {:docker {:resolve-image "always" :with-registry-auth true} :docker-config docker-config}
                    {#"GET /v1.47/distribution/" distribution})]
      (is (= {"username" "u" "password" "p" "serveraddress" "https://index.docker.io/v1/"}
             (select-keys (auth-json (first (distribution-requests reqs))) ["username" "password" "serveraddress"])))
      (is (= (get-in (first (distribution-requests reqs)) [:headers "x-registry-auth"])
             (get-in (request reqs #"POST /v1.47/services/create") [:headers "x-registry-auth"])))))
  (testing "on, existing service: sent with the update too"
    (let [reqs (run {:docker {:resolve-image "never" :with-registry-auth true} :docker-config docker-config}
                    {#"GET /v1.47/services\?" (services-route old-service)
                     #"POST /v1.47/services/svc-old/update" (fake/response 200 "{}")})]
      (is (= "u" (get (auth-json (updated reqs)) "username")))))
  (testing "off: no credentials anywhere, even when config.json has them"
    (let [reqs (run {:docker {:resolve-image "always" :with-registry-auth false} :docker-config docker-config}
                    {#"GET /v1.47/distribution/" distribution})]
      (is (seq reqs))
      (is (not-any? #(get-in % [:headers "x-registry-auth"]) reqs)))))

(deftest prune
  (let [stray (assoc-in old-service ["Spec" "Name"] "web_old")
        extra {#"GET /v1.47/services\?" (services-route (assoc stray "ID" "svc-stray"))
               #"DELETE /v1.47/services/svc-stray" (fake/response 200 "")}]
    (testing "on: services of the stack that left the file are removed"
      (is (request (run {:docker {:prune true}} extra) #"DELETE /v1.47/services/svc-stray")))
    (testing "off (default): left alone"
      (is (nil? (request (run {} extra) #"DELETE "))))
    (testing "dry run: only logged"
      (is (nil? (request (run {:docker {:prune true} :dry-run true} extra) #"DELETE "))))))
