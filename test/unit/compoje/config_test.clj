(ns unit.compoje.config-test
  (:require [clojure.test :refer :all]
            [flatland.ordered.map :refer [ordered-map]]
            [compoje.config :as c]))

(deftest config-test

  (testing "config-name->template-dir \n"
    (let [hash (c/config-name->template-dir
                "test/unit/compoje/config_test.clj")]
      (is (= hash "test/unit/compoje"))))

  (testing "load config from data (file)"
    (let [file (char-array "x-compoje:
                              docker:
                                stack: nginx
                              values:
                                simple: 2")
          args ["values.complex=3" "docker.context=remote-swarm"]
          res (c/load-config! file args)
          expected (ordered-map {:docker (ordered-map
                                          {:stack "nginx"
                                           :context "remote-swarm"})
                                 :values (ordered-map
                                          {:simple 2
                                           :complex "3"})})]
      (is (= expected res)))))

(comment

  (run-tests))

(deftest legacy-compoje-key-test
  (testing "a legacy top-level compoje key is still read"
    (is (= {:docker {:stack "nginx"}}
           (c/file->config! (char-array "compoje:\n  docker:\n    stack: nginx\n"))))))
