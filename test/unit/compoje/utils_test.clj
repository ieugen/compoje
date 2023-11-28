(ns unit.compoje.utils-test
  (:require [clojure.test :refer :all]
            [compoje.utils :as u]))

(deftest deep-merge-test
  (testing "deep-merge can merges a few maps\n"
    (is (= {:a {:b {:c 1 :d 2}}}
           (u/deep-merge {}
                         {:a {:b {:c 1}}}
                         {:a {:b {:d 2}}})))))

(deftest strip-quotes-test
  (testing "strip-quotes removes single and double quotes\n"
    (let [stripped (map u/strip-quotes ["" "\"" "a" "\" a \"" "'a'"])]
      (is (= stripped '("" "" "a" " a " "a"))))))

(deftest set-args->map-test
  (testing "convert empty args to map is empty map"
    (let [args []
          result (u/set-args->map args)]
      (is (= {} result))))

  (testing "convert some args to map is gives map"
    (let [args ["first.arg=2"
                "second.arg=\"this is a string with spaces\""]
          result (u/set-args->map args)]
      (is (= {:first {:arg "2"}
              :second {:arg "this is a string with spaces"}}
             result)))))

(comment

  (run-test set-args->map-test)
  )