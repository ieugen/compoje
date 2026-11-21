(ns unit.ansible-vault.yamlstar-test
  (:require [ansible-vault.yamlstar :as avy]
            [clojure.test :refer [deftest is testing]]
            [unit.ansible-vault.fixtures :as fx]))

(deftest load-yaml-inline-vault-values
  (let [{:keys [file password-file expected]} (:inline fx/manifest)]
    (testing "values made by ansible-vault encrypt_string are decrypted; plain values and nesting are kept"
      (is (= expected (avy/load-yaml (slurp (fx/path file)) (fx/password password-file)))))
    (testing "wrong password fails"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Decryption failed"
                            (avy/load-yaml (slurp (fx/path file)) (fx/password "pw-wrong.txt")))))))

(deftest load-yaml-without-vault-values
  (is (= {"a" 1 "b" {"c" ["x" "y"]}}
         (avy/load-yaml "a: 1\nb:\n  c: [x, y]\n" "unused"))))
