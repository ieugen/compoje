(ns integration.vault-test
  (:require [clj-test-containers.core :as tc]
            [clojure.set :as set]
            [clojure.test :refer [deftest is run-all-tests testing]]
            [compoje.providers :as providers]
            [compoje.providers.vault :as vp]
            [taoensso.timbre :as log]
            [vault.sys.health :as health]
            [vault.secret.kv.v2 :as kv2]))

(log/merge-config! {:level :info
                    :ns-filter {:allow #{"*"}
                                :deny #{"com.github.dockerjava.*"}}})

(def ^:private vault-root-token "myroot")

(def ^:private default-container-cfg {:image-name    "hashicorp/vault:1.14.1"
                                      :exposed-ports [8200]
                                      :env-vars      {"SKIP_SETCAP" "true"
                                                      "VAULT_DEV_ROOT_TOKEN_ID" vault-root-token}
                                      :wait-for      {:wait-strategy :port}})

(defn ^:private container->vault-addr
  [container]
  (let [host (:host container)
        port (get (:mapped-ports container) 8200)]
    (str "http://" host ":" port)))

(defn ^:private container->vault-config
  [container]
  (let [addr (container->vault-addr container)]
    {:token vault-root-token
     :addr addr}))

(defn ^:private container->client
  [container]
  (let [config (container->vault-config container)
        client (vp/authenticated-client config)]
    client))

(defn vault-kv-fixture
  [client secret-spec]
  (let [{:keys [mount-path secret-path data]} secret-spec
        client (kv2/with-mount client mount-path)]
    (kv2/write-secret! client secret-path data)))

(defn with-vault
  "Start and stop a vault instance around tests.

   f is a fn that receives a map with container data (see clj-containers).
   Returns a vector of test assertions:
   https://clojuredocs.org/clojure.test/testing#example-5817a6bde4b024b73ca35a20
   "
  ([f]
   (let [container (-> (tc/create default-container-cfg)
                       (tc/start!))]
     (f container)
     ;; stop container
     (tc/stop! container))))

(deftest vault-provider-test

  (testing "Can create vault secret provider\n"
    (let [p (vp/->VaultSecretsProvider)]
      (is (satisfies? providers/SecretsProvider p))
      (is (= :compoje.providers.vault/vault (providers/provider-name p)))))

  (testing "Vault container starts and we can connect\n"
    (with-vault
      (fn [container]
        (let [client (container->client container)
              status (health/read-health client nil)
              expected-keys #{:initialized :cluster-name :sealed :version}]
          ;; (tap> status)
          ;; return a vector
          [(is (set/subset? expected-keys (into #{} (keys status))))
           (is (= true (:initialized status)))
           (is (= false (:sealed status)))]))))

  #_(testing "Fails when context is nil\n"
      (with-vault
        (fn [container]
          (let [addr (container->vault-addr container)
                v (vp/->VaultSecretsProvider)]
            ;; return a vector
            (providers/run v nil)))))

  (testing "Gets a secret with a valid spec\n"
    (with-vault
      (fn [container]
        (let [cfg (container->vault-config container)
              client (container->client container)
              _ (vault-kv-fixture client {:mount-path "secret"
                                          :secret-path "secret-foo"
                                          :data {:a "b"}})
              v (vp/->VaultSecretsProvider)
              context {:config cfg
                       :template-dir "."
                       :secret-spec {:name "cert-key"
                                     :provider :dre-vault
                                     :mount-path "secret"
                                     :secret-path "secret-foo"}}
              result (providers/run v context)]
          (is (= result {:a "b"})))))))

(comment
  (run-all-tests #"providers.vault-test"))