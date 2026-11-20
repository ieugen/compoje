(ns unit.compoje.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [compoje.context :as c]))

(deftest context-test
  (testing "load-context! builds a context\n"
    (let [context (c/load-context! "/tmp/a" {:values {:a 1}} {:b 2})
          context (dissoc context :work-dir)]

      (is (= context {:template-dir "/tmp/a"
                      :secrets {:b 2}
                      :values {:a 1}})))))

(deftest config->context-test
  (testing "config->context deep-merges extra maps over the config"
    (let [context (c/config->context {:values {:a 1} :secrets []}
                                     {:template-dir "/tmp/a"}
                                     {:docker {:compose-file "/tmp/a/stack.generated.yml"}})]
      (is (= {:values {:a 1}
              :secrets []
              :template-dir "/tmp/a"
              :docker {:compose-file "/tmp/a/stack.generated.yml"}}
             (dissoc context :work-dir))))))
