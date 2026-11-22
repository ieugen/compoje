(ns bb-test-runner
  (:require
   [bb.deps-why-test]
   [bb.install-test]
   [clojure.test :as t]
   [unit.ansible-vault.core-test]
   [unit.ansible-vault.yamlstar-test]
   [unit.compoje.deploy.api-test]
   [unit.compoje.deploy.swarm-convert-test]
   [unit.compoje.docker.client-test]
   [unit.compoje.docker.endpoint-test]
   [unit.compoje.docker.tls-test]
   [unit.compoje.init-test]
   [unit.compoje.migrate-test]
   [unit.compoje.providers.ansible-vault-test]
   [unit.compoje.providers.file-test]
   [unit.rct-test]))

(def test-namespaces
  "Tests that must pass in Babashka (the production runtime)."
  ['unit.rct-test
   'bb.deps-why-test
   'bb.install-test
   'unit.ansible-vault.core-test
   'unit.ansible-vault.yamlstar-test
   'unit.compoje.deploy.api-test
   'unit.compoje.deploy.swarm-convert-test
   'unit.compoje.docker.client-test
   'unit.compoje.docker.endpoint-test
   'unit.compoje.docker.tls-test
   'unit.compoje.init-test
   'unit.compoje.migrate-test
   'unit.compoje.providers.ansible-vault-test
   'unit.compoje.providers.file-test])

(defn run-tests [& _args]
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (when (or (pos? fail)
              (pos? error))
      (System/exit 1))))
