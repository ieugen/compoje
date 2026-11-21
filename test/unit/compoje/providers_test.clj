(ns unit.compoje.providers-test
  (:require [clojure.test :refer [deftest is testing]]
            [compoje.providers :as p]))

(deftest provide-secrets-test
  (testing "provider type from YAML is a string; it resolves to the registered keyword"
    (p/register-provider! :unit.compoje.providers-test/fake
                          (reify p/SecretsProvider
                            (provider-name [_] :unit.compoje.providers-test/fake)
                            (run [_ {:keys [secret-spec]}] {:got (:name secret-spec)})))
    (is (= {:cert {:got "cert"}}
           (p/provide-secrets {:template-dir "."
                               :providers [{:name "my-fake"
                                            :type "unit.compoje.providers-test/fake"}]
                               :secrets [{:name "cert" :provider "my-fake"}]})))))
