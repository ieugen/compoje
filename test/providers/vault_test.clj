(ns providers.vault-test
  (:require [clj-test-containers.core :as tc]
            [clojure.set :as set]
            [clojure.test :refer [deftest is run-all-tests testing]]
            [compoje.providers :as providers]
            [compoje.providers.vault :as vp]
            [taoensso.timbre :as log]
            [vault.core :as vault]))

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

(defn ^:private container->client
  [container]
  (let [addr (container->vault-addr container)
        client (vp/authenticated-client {:token vault-root-token
                                         :addr addr})]
    client))

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
              status (vault/status client)
              expected-keys #{:initialized :cluster-name :sealed :version}]
          ;; return a vector
          [(is (set/subset? expected-keys (into #{} (keys status))))
           (is (= true (:initialized status)))
           (is (= false (:sealed status)))]))))

  (testing "Fails when secret spec is missing\n"
    (with-vault
      (fn [container]
        (let [addr (container->vault-addr container)
              v (vp/->VaultSecretsProvider)]
            ;; return a vector
          (providers/run v nil))))))


(comment
  (run-all-tests #"providers.vault-test"))