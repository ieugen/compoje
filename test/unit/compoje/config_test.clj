(ns unit.compoje.config-test
  (:require [clojure.test :refer :all]
            [compoje.config :as c]))

(deftest config-test

  (testing "config-name->template-dir \n"
    (let [hash (c/config-name->template-dir
                "test/unit/compoje/config_test.clj")]
      (is (= hash "test/unit/compoje"))))

  (testing "load config from file"
    #_(let [c/load-config! ]))

  )



(comment

  (run-tests))