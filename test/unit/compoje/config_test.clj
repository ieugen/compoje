(ns unit.compoje.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [compoje.config :as c]))

(deftest config-test

  (testing "config-name->template-dir \n"
    (let [hash (c/config-name->template-dir "test/unit/compoje/config_test.clj")]
      (is (= hash "test/unit/compoje")))))