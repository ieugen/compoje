(ns providers.vault-test
  (:require [clojure.test :refer [deftest testing is
                                  run-all-tests
                                  run-test]]
            [clj-test-containers.core :as tc]))

(deftest vault-provider-test
  (testing "Context of the test assertions"
    (let [container (-> (tc/create {:image-name    "hashicorp/vault:1.14.1"
                                    :exposed-ports [8200]
                                    :env-vars      {"SKIP_SETCAP" "true"
                                                    "VAULT_DEV_ROOT_TOKEN_ID" "myroot"}
                                    :wait-for      {:wait-strategy :port}})
                        (tc/start!))]
      (is (= 1 1))
      (tc/stop! container))))


(comment
   (run-all-tests #"providers.vault-test")
  )