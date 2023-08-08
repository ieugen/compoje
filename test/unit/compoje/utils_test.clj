(ns unit.compoje.utils-test
  (:require [clojure.test :refer [deftest is testing]]
            [compoje.utils :as u]))

(deftest utils-test
  (testing "deep-merge can merges a few maps\n"
    (is (= {:a {:b {:c 1 :d 2}}}
           (u/deep-merge {}
                         {:a {:b {:c 1}}}
                         {:a {:b {:d 2}}})))))