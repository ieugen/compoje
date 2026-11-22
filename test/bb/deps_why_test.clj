(ns bb.deps-why-test
  "Tests for script/deps_why.clj (`bb deps-why`)."
  (:require [clojure.test :refer [deftest is testing]]
            [deps-why :as dw]))

(def tree
  (str "org.clojure/clojure 1.12.7\n"
       "  . org.clojure/spec.alpha 0.5.238\n"
       "djblue/portal 0.69.2\n"
       "  . com.cognitect/transit-clj 1.1.357\n"
       "    . com.cognitect/transit-java 1.1.403\n"
       "      . org.msgpack/msgpack-core 0.9.11\n"
       "org.testcontainers/testcontainers 2.0.5\n"
       "  X org.slf4j/slf4j-api 1.7.36 :older-version\n"
       "  . com.github.docker-java/docker-java-transport-zerodep 3.7.1\n"
       "    . com.github.docker-java/docker-java-transport 3.7.1\n"
       "    X org.slf4j/slf4j-api 1.7.30 :older-version\n"))

(deftest tree-paths
  (testing "path from the top-level dependency down"
    (is (= ["djblue/portal 0.69.2 -> com.cognitect/transit-clj 1.1.357 -> com.cognitect/transit-java 1.1.403 -> org.msgpack/msgpack-core 0.9.11"]
           (map dw/format-path (dw/tree-paths tree "org.msgpack/msgpack-core")))))
  (testing "every occurrence, excluded ones marked as not used"
    (is (= ["org.testcontainers/testcontainers 2.0.5 -> org.slf4j/slf4j-api 1.7.36  (not used: :older-version)"
            "org.testcontainers/testcontainers 2.0.5 -> com.github.docker-java/docker-java-transport-zerodep 3.7.1 -> org.slf4j/slf4j-api 1.7.30  (not used: :older-version)"]
           (map dw/format-path (dw/tree-paths tree "org.slf4j/slf4j-api")))))
  (testing "siblings don't leak into a path"
    (is (= 2 (count (first (dw/tree-paths tree "com.github.docker-java/docker-java-transport-zerodep"))))))
  (is (empty? (dw/tree-paths tree "no/such"))))

(deftest aliases
  (is (= [:dev :test] (dw/aliases {:deps {} :aliases {:test {} :dev {}}}))))
