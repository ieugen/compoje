(ns unit.compoje.context-test
  (:require [clojure.test :refer [deftest is testing]]
            [compoje.context :as c]))

(deftest context-test
  (testing "load-context! builds a context\n"
    (let [context (c/load-context! "/tmp/a" {:values {:a 1}} {:b 2})
          context (dissoc context :work-dir)]

      (is (= context {:config {:values {:a 1}}
                      :template-dir "/tmp/a"
                      :output "stack.generated.yml"
                      :secrets {:b 2}
                      :values {:a 1 :b 2}})))))