(ns unit.compoje.migrate-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [compoje.config :as config]
            [compoje.migrate :as migrate]))

(def fixtures "test-resources/migrate")

(defn- copy-fixture
  "Copy fixture directory `name` to a fresh temp dir; return the copy's path."
  [name]
  (let [dest (fs/path (fs/create-temp-dir {:prefix "compoje-migrate-"}) name)]
    (fs/copy-tree (fs/path fixtures name) dest)
    (str dest)))

(defn- error-message [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(def expected-config
  {:docker {:stack "nginx" :prune true}
   :providers [{:name "my-vault"
                :type "compoje.providers.vault/vault"
                :addr "http://localhost:8200"}]
   :secrets [{:name "cert"
              :provider "my-vault"
              :local-path "nginx/cert.crt"
              :spit-key "cert.crt"
              :mount-path "secret"
              :secret-path "nginx"}]
   :values {:resource-reservations-mem "128m"
            :replicas 1
            :cpus "4"
            :features ["gzip"]}})

(deftest migrate-nginx
  (let [dir (copy-fixture "nginx")
        template-file (str (fs/file dir "stack.tpl.yml"))
        original (slurp template-file)]
    (is (= template-file (migrate/migrate! dir {})))
    (testing "config round-trips through x-compoje"
      (is (= expected-config (config/file->config! template-file))))
    (testing "the original template follows the x-compoje section unchanged"
      (is (str/starts-with? (slurp template-file) "x-compoje:\n"))
      (is (str/ends-with? (slurp template-file) (str "\n\n" original))))
    (testing "compoje.edn is kept"
      (is (fs/exists? (fs/file dir "compoje.edn"))))
    (testing "a second run is refused"
      (is (re-find #"already has an x-compoje section"
                   (error-message #(migrate/migrate! dir {})))))))

(deftest migrate-dry-run
  (let [dir (copy-fixture "nginx")
        template-file (fs/file dir "stack.tpl.yml")
        original (slurp template-file)
        result (migrate/migrate! dir {:dry-run true})]
    (is (str/starts-with? result "x-compoje:\n"))
    (is (str/ends-with? result original))
    (is (= original (slurp template-file)) "dry-run writes nothing")))

(deftest migrate-aero-tags
  (let [dir (copy-fixture "aero-tags")
        template-file (fs/file dir "stack.tpl.yml")
        original (slurp template-file)
        msg (error-message #(migrate/migrate! dir {}))]
    (is (re-find #"aero tags" msg))
    (is (str/includes? msg "[:docker :stack] #profile"))
    (is (str/includes? msg "[:providers 0 :addr] #or [#env \"VAULT_ADDR\""))
    (is (= original (slurp template-file)) "nothing written")))

(deftest migrate-missing-files
  (testing "missing compoje.edn"
    (let [dir (copy-fixture "nginx")]
      (fs/delete (fs/file dir "compoje.edn"))
      (is (re-find #"Missing .*compoje\.edn" (error-message #(migrate/migrate! dir {}))))))
  (testing "missing stack.tpl.yml"
    (let [dir (copy-fixture "nginx")]
      (fs/delete (fs/file dir "stack.tpl.yml"))
      (is (re-find #"Missing .*stack\.tpl\.yml" (error-message #(migrate/migrate! dir {}))))))
  (testing "not a directory"
    (is (re-find #"Not a directory" (error-message #(migrate/migrate! "no/such/dir" {}))))))
