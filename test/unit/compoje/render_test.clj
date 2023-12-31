(ns unit.compoje.render-test
  (:require [clojure.test :refer [deftest is testing run-test]]
            [compoje.render :as r]))

(deftest render-test
  (testing "hash-string produces expected hash\n"
    (let [hash (r/hash-string "a" "md5")]
      (is (= hash "0cc175b9c0f1b6a831c399e269772661"))))

  (testing "hash-file produces expected hash\n"
    (let [stripped (r/hash-file "test/resources/simple.tpl")]
      (is (= stripped "6fb9ee3385a0d6eb97dcc5d4e06b165f"))))

  (testing "strip-quotes removes single and double quotes\n"
    (let [stripped (map r/strip-quotes ["" "\"" "a" "\" a \"" "'a'"])]
      (is (= stripped '("" "" "a" " a " "a")))))

  (testing "load-template produces the expected data\n"
    (let [tpl (r/load-template "test/resources/simple.tpl")]
      (is (= tpl "Hello <{ values.hello }>!"))))

  (testing "render can render simple.tpl\n"
    (let [tpl (r/render "test/resources/simple.tpl"
                        {:values {:hello "world"}})]
      (is (= tpl "Hello world!")))))


(deftest test-to-yaml-render-helper

  (testing "render template with to-yaml \n"
    (let [res (r/render "test/resources/to-yaml.01.tpl"
                        {:props [{:a 1}]})]
      (spit "test/resources/to-yaml.01.tpl.generated.yml" res)
      (is (= res "- name: test\n- properties:\n  - a: 1\n")))))

(comment

  (run-test test-to-yaml-render-helper)
  )