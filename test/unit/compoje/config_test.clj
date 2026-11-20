(ns unit.compoje.config-test
  (:require [clojure.test :refer :all]
            [compoje.config :as c]))

(deftest config-test

  (testing "config-name->template-dir \n"
    (let [hash (c/config-name->template-dir
                "test/unit/compoje/config_test.clj")]
      (is (= hash "test/unit/compoje")))
    (is (= "." (c/config-name->template-dir "stack.tpl.yml"))
        "bare file name resolves to the current directory, not the filesystem root"))

  (testing "load config from data (file)"
    (let [file (char-array "x-compoje:
                              docker:
                                stack: nginx
                              values:
                                simple: 2")
          args ["values.complex=3" "docker.context=remote-swarm"]
          res (c/load-config! file args)
          expected {:docker {:stack "nginx"
                             :context "remote-swarm"}
                    :values {:simple 2
                             :complex "3"}}]
      (is (= expected res)))))

(comment

  (run-tests))

(deftest legacy-compoje-key-test
  (testing "a legacy top-level compoje key is still read"
    (is (= {:docker {:stack "nginx"}}
           (c/file->config! (char-array "compoje:\n  docker:\n    stack: nginx\n"))))))

(deftest yaml-1-2-test
  (testing "YAML 1.2: yes/on/off stay strings, keys are keywordized, sequences are vectors"
    (let [cfg (c/file->config! (char-array "x-compoje:\n  values:\n    a: yes\n    b: on\n    c: [1, 2]\n"))]
      (is (= {:values {:a "yes" :b "on" :c [1 2]}} cfg))
      (is (vector? (get-in cfg [:values :c]))))))
