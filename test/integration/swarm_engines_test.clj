(ns integration.swarm-engines-test
  "End-to-end tests of the docker-api engine against a throwaway swarm
   (Docker-in-Docker with TLS and sshd, test-resources/swarm/dind/Dockerfile).
   Needs Docker (privileged containers) and a local `ssh` client.

   - engines-are-equivalent: the reference stack deployed with
     `docker stack deploy` and with the docker-api engine gives the same
     services, networks, secrets and configs.
   - managed secrets over ssh (dial-stdio): created from memory, no files.
   - ssh socket forwarding and mutual TLS transports."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-test-containers.core :as tc]
            [clojure.data :as data]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [compoje.cli :as cli]
            [compoje.deploy.api :as api]
            [compoje.deploy.swarm-convert :as convert]
            [compoje.docker.client :as client]
            [compoje.render :as render]
            [integration.ansible-vault-helpers :as h])
  (:import (org.testcontainers.containers GenericContainer)))

(def dockerfile "test-resources/swarm/dind/Dockerfile")
(def ref-dir (str (fs/absolutize "test-resources/swarm/ref-stack")))

(def ^:dynamic *swarm* nil)

(defn- exec! [container & cmd]
  (let [{:keys [exit-code stdout stderr] :as r} (tc/execute-command! container cmd)]
    (when-not (zero? exit-code)
      (throw (ex-info (str "exec failed: " (str/join " " cmd) ": " stderr stdout) r)))
    r))

(defn- wait-until [what timeout-ms f]
  (loop [waited 0]
    (or (try (f) (catch Exception _ nil))
        (if (> waited timeout-ms)
          (throw (ex-info (str "Timed out waiting for " what) {}))
          (do (Thread/sleep 500) (recur (+ waited 500)))))))

(defn start-swarm! []
  (let [c (tc/create-from-docker-file {:docker-file dockerfile :exposed-ports [22 2376]})
        _ (.withPrivilegedMode ^GenericContainer (:container c) true)
        c (tc/start! c)
        work (str (fs/create-temp-dir {:prefix "compoje-swarm-"}))
        key-file (str (fs/path work "id_ed25519"))]
    (wait-until "dockerd" 120000 #(zero? (:exit-code (tc/execute-command! c ["docker" "info"]))))
    (exec! c "docker" "swarm" "init")
    (doseq [f ["ca.pem" "cert.pem" "key.pem"]]
      (fs/write-bytes (fs/path work f) (h/get-bytes c (str "/certs/client/" f))))
    (p/shell {:out :string :err :string} "ssh-keygen" "-q" "-t" "ed25519" "-N" "" "-f" key-file)
    (h/put-file! c "/root/.ssh/authorized_keys" (slurp (str key-file ".pub")))
    ;; copied files keep the host uid; sshd (StrictModes) wants root's own file
    (exec! c "chown" "root:root" "/root/.ssh/authorized_keys")
    (exec! c "chmod" "600" "/root/.ssh/authorized_keys")
    (let [host (:host c)
          tls-port (get (:mapped-ports c) 2376)
          ssh-port (get (:mapped-ports c) 22)]
      {:container c
       :work work
       :docker-env {"DOCKER_HOST" (str "tcp://" host ":" tls-port)
                    "DOCKER_TLS_VERIFY" "1"
                    "DOCKER_CERT_PATH" work}
       :tls {:scheme :tcp :host host :port tls-port :host-url (str "tcp://" host ":" tls-port)
             :tls {:ca (str (fs/path work "ca.pem")) :cert (str (fs/path work "cert.pem"))
                   :key (str (fs/path work "key.pem")) :verify true}}
       :ssh {:scheme :ssh :host host :port ssh-port :user "root" :host-url (str "ssh://root@" host ":" ssh-port)
             :ssh-command ["ssh" "-i" key-file "-o" "StrictHostKeyChecking=no"
                           "-o" "UserKnownHostsFile=/dev/null" "-o" "LogLevel=ERROR"]}})))

(use-fixtures :once (fn [f]
                      (let [s (start-swarm!)]
                        (try (binding [*swarm* s] (f))
                             (finally (tc/stop! (:container s))
                                      (fs/delete-tree (:work s)))))))

;; ------------------------------------------------------------------ snapshots

(defn- label-filter [stack]
  {:filters (cheshire.core/generate-string {"label" [(str convert/namespace-label "=" stack)]})})

(defn snapshot
  "What a stack consists of, comparable across deployments: service, secret
   and config specs and network settings; IDs replaced by names."
  [conn stack]
  (let [get-all #(:body (client/request conn :get % {:query (label-filter stack)}))
        networks (get-all "/networks")
        net-name (into {} (map (juxt #(get % "Id") #(get % "Name")) networks))
        no-ids (fn no-ids [x]
                 (cond
                   (map? x) (into {} (for [[k v] x :when (not (#{"SecretID" "ConfigID"} k))]
                                       [k (if (= "Target" k) (get net-name v v) (no-ids v))]))
                   (sequential? x) (mapv no-ids x)
                   :else x))]
    {:services (into {} (for [s (get-all "/services")] [(get-in s ["Spec" "Name"]) (no-ids (get s "Spec"))]))
     :networks (into {} (for [n networks]
                          [(get n "Name")
                           (-> (select-keys n ["Driver" "Internal" "Attachable" "Labels" "Scope"])
                               (assoc "Options" (into {} (remove #(str/includes? (key %) "vxlanid") (get n "Options"))))
                               (assoc "UserSubnets" (when (get-in n ["Labels" "com.example.net"]) nil)))]))
     :secrets (into {} (for [s (get-all "/secrets")] [(get-in s ["Spec" "Name"]) (get s "Spec")]))
     :configs (into {} (for [s (get-all "/configs")] [(get-in s ["Spec" "Name"]) (get s "Spec")]))}))

(defn remove-stack!
  "Remove a stack's services, then (once tasks are gone) networks, secrets
   and configs."
  [conn stack]
  (let [get-all #(:body (client/request conn :get % {:query (label-filter stack)}))]
    (doseq [s (get-all "/services")]
      (client/request conn :delete (str "/services/" (get s "ID"))))
    (doseq [kind ["/networks" "/secrets" "/configs"]
            o (get-all kind)]
      (wait-until (str "removing " kind) 120000
                  #(< (:status (client/request conn :delete (str kind "/" (or (get o "Id") (get o "ID")))
                                               {:ok-statuses #{404}}))
                      300)))))

;; ------------------------------------------------------------------ tests

(deftest engines-are-equivalent
  (let [{:keys [tls docker-env]} *swarm*]
    (client/with-connection [conn tls]
      (testing "docker stack deploy (reference)"
        (p/shell {:dir ref-dir :extra-env docker-env :out :string :err :string}
                 "docker" "stack" "deploy" "--resolve-image" "never" "-c" "stack.yml" "ref"))
      (let [cli-snapshot (snapshot conn "ref")]
        (remove-stack! conn "ref")
        (testing "docker-api engine"
          (api/deploy! (convert/parse-stack (slurp (str (fs/path ref-dir "stack.yml"))))
                       {:endpoint tls
                        :docker {:stack "ref" :resolve-image "never" :with-registry-auth false}
                        :objects []
                        :template-dir ref-dir
                        :docker-config {}}))
        (let [api-snapshot (snapshot conn "ref")
              [only-cli only-api] (data/diff cli-snapshot api-snapshot)]
          (is (= 2 (count (:services cli-snapshot))))
          (is (= 3 (count (:networks cli-snapshot))))
          (is (= cli-snapshot api-snapshot)
              (str "only docker stack deploy: " (pr-str only-cli) "\nonly docker-api: " (pr-str only-api))))
        (remove-stack! conn "ref")))))

(deftest managed-secrets-over-ssh
  (let [{:keys [ssh]} *swarm*
        stack-dir "example-stacks/file"
        files-before (set (map str (fs/glob stack-dir "**")))]
    (cli/register-default-providers!)
    (let [ctx (cli/deploy-context {:stack-file (str stack-dir "/stack.tpl.yml")})
          text (render/render (str stack-dir "/stack.tpl.yml") ctx {})
          [_ objects parsed] (cli/prepare-stack ctx text)]
      (api/deploy! parsed {:endpoint ssh
                           :docker (assoc (:docker ctx) :resolve-image "never" :with-registry-auth false)
                           :objects objects
                           :template-dir (:template-dir ctx)
                           :docker-config {}})
      (client/with-connection [conn ssh]
        (testing "secret and config created from memory, labelled, referenced by the service"
          (let [{:keys [secrets configs services]} (snapshot conn "file-example")
                secret-name (:name (first (filter #(= :secret (:kind %)) objects)))]
            (is (re-matches #"file-example_api_token-[0-9a-f]{10}" secret-name))
            (is (= {"com.docker.stack.namespace" "file-example" "com.docker.stack.managed-by" "compoje"}
                   (get-in secrets [secret-name "Labels"])))
            (is (= 1 (count configs)))
            (is (= secret-name (get-in services ["file-example_app" "TaskTemplate" "ContainerSpec" "Secrets" 0 "SecretName"])))))
        (testing "nothing was written next to the stack"
          (is (= files-before (set (map str (fs/glob stack-dir "**"))))))
        (remove-stack! conn "file-example")))))

(deftest ssh-socket-forwarding
  (client/with-connection [conn (assoc (:ssh *swarm*) :ssh-mode :forward)]
    (is (= "OK" (:body (client/request conn :get "/_ping"))))
    (is (true? (get-in (client/request conn :get "/info") [:body "Swarm" "ControlAvailable"])))))

(deftest mutual-tls-to-dind
  (testing "dind's own CA and client certificate, hostname verified"
    (client/with-connection [conn (:tls *swarm*)]
      (is (= "OK" (:body (client/request conn :get "/_ping"))))))
  (testing "without the client certificate the daemon refuses"
    (is (thrown? Exception (client/open (update (:tls *swarm*) :tls dissoc :cert :key))))))
