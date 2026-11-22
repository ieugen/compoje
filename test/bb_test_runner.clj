(ns bb-test-runner
  (:require
   [clojure.test :as t]
   [unit.ansible-vault.core-test]
   [unit.ansible-vault.yamlstar-test]
   [unit.compoje.docker.client-test]
   [unit.compoje.docker.endpoint-test]
   [unit.compoje.docker.tls-test]
   [unit.compoje.migrate-test]
   [unit.compoje.providers.ansible-vault-test]
   [unit.compoje.providers.file-test]
   [unit.rct-test]))

(def test-namespaces
  "Tests that must pass in Babashka (the production runtime)."
  ['unit.rct-test
   'unit.ansible-vault.core-test
   'unit.ansible-vault.yamlstar-test
   'unit.compoje.docker.client-test
   'unit.compoje.docker.endpoint-test
   'unit.compoje.docker.tls-test
   'unit.compoje.migrate-test
   'unit.compoje.providers.ansible-vault-test
   'unit.compoje.providers.file-test])

(defn run-tests [& _args]
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (when (or (pos? fail)
              (pos? error))
      (System/exit 1))))
