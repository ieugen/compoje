(ns unit.compoje.migrate-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [compoje.cli :as cli]
            [compoje.config :as config]
            [compoje.context :as context]
            [compoje.migrate :as migrate]
            [compoje.migrate-secrets :as ms]
            [compoje.render]
            [yamlstar.core :as yaml]))

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
    (is (= {:file template-file :changed true :edn true}
           (select-keys (migrate/migrate! dir {}) [:file :changed :edn])))
    (testing "config round-trips through x-compoje"
      (is (= expected-config (config/file->config! template-file))))
    (testing "the original template follows the x-compoje section unchanged"
      (is (str/starts-with? (slurp template-file) "x-compoje:\n"))
      (is (str/ends-with? (slurp template-file) (str "\n\n" original))))
    (testing "compoje.edn is kept"
      (is (fs/exists? (fs/file dir "compoje.edn"))))
    (testing "the unused local-path secret is reported, not converted"
      (is (re-find #"not used as a Docker secret"
                   (-> (migrate/migrate! (copy-fixture "nginx") {:dry-run true}) :skipped first :reason))))
    (testing "a second run with compoje.edn still present is refused"
      (is (re-find #"already has an x-compoje section, and .*compoje.edn exists too"
                   (error-message #(migrate/migrate! dir {})))))))

(deftest migrate-dry-run
  (let [dir (copy-fixture "nginx")
        template-file (fs/file dir "stack.tpl.yml")
        original (slurp template-file)
        result (:text (migrate/migrate! dir {:dry-run true}))]
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
  (testing "no compoje.edn and no x-compoje section"
    (let [dir (copy-fixture "nginx")]
      (fs/delete (fs/file dir "compoje.edn"))
      (is (re-find #"Nothing to migrate: .*compoje\.edn is missing" (error-message #(migrate/migrate! dir {}))))))
  (testing "missing stack.tpl.yml"
    (let [dir (copy-fixture "nginx")]
      (fs/delete (fs/file dir "stack.tpl.yml"))
      (is (re-find #"Missing .*stack\.tpl\.yml" (error-message #(migrate/migrate! dir {}))))))
  (testing "not a directory"
    (is (re-find #"Not a directory" (error-message #(migrate/migrate! "no/such/dir" {}))))))

;; ------------------------------------------------------------------ local-path secrets

(deftest migrate-local-path-secrets
  (let [dir (copy-fixture "local-path")
        template-file (str (fs/file dir "stack.tpl.yml"))
        result (migrate/migrate! dir {})
        data (yaml/load (slurp template-file))]
    (testing "two secrets converted, the one also used elsewhere skipped"
      (is (= [["app" "secrets.app.db_password" [["secrets" "db_password"]]]
              ["token" "secrets.token" [["secrets" "token"]]]]
             (map (juxt :secret :from :defs) (:converted result))))
      (is (= ["cert"] (map :secret (:skipped result))))
      (is (re-find #"also used at line \d+" (-> result :skipped first :reason))))
    (testing "definitions become x-compoje from; explicit name and labels kept, file-hash name dropped"
      (is (= {"x-compoje" {"from" "secrets.app.db_password"}} (get-in data ["secrets" "db_password"])))
      (is (= {"x-compoje" {"from" "secrets.token"} "name" "token-v1" "labels" {"team" "ops"}}
             (get-in data ["secrets" "token"])))
      (is (= {"file" "generated/cert.pem"} (get-in data ["secrets" "cert"]))))
    (testing "local-path and spit-key removed from converted secrets only"
      (let [secrets (:secrets (config/file->config! template-file))]
        (is (= [nil nil "generated/cert.pem"] (map :local-path secrets)))
        (is (every? nil? (map :spit-key secrets)))))
    (testing "0.3.0-style references to provider results now use secrets.<name>; paths in tags untouched"
      (let [text (slurp template-file)]
        (is (str/includes? text "DB_USER: <{ secrets.app.db_user }>"))
        (is (str/includes? text "<% file-path 'generated/cert.pem' %>"))))
    (testing "comments survive; the one about the removed local-path goes with it"
      (let [text (slurp template-file)]
        (is (str/includes? text "# whole file encrypted with `ansible-vault encrypt`"))
        (is (str/includes? text "# also mounted with file-path below: must be skipped"))
        (is (not (str/includes? text "# write one decrypted value to a file")))))
    (testing "old plaintext files are listed, not deleted"
      (is (= [(str (fs/file dir "generated/db_password.txt"))] (:plaintext-files result)))
      (is (fs/exists? (fs/file dir "generated/db_password.txt"))))
    (testing "a second run has nothing to convert"
      (let [again (migrate/migrate! dir {})]
        (is (false? (:changed again)))
        (is (= ["cert"] (map :secret (:skipped again))))))))

(deftest migrate-edn-with-secrets
  (let [dir (copy-fixture "edn-secrets")
        result (migrate/migrate! dir {})]
    (is (:edn result))
    (is (= ["db"] (map :secret (:converted result))))
    (is (re-find #"spit-key cert.key contains a dot" (-> result :skipped first :reason)))
    (testing "the migrated stack creates the converted secret from memory"
      ;; the skipped secret still uses local-path: its provider would write this file
      (fs/create-dirs (fs/file dir "generated"))
      (spit (str (fs/file dir "generated/cert.key")) "key")
      (let [stack-file (str (fs/file dir "stack.tpl.yml"))
            ctx (context/final-context (cli/cli-config->context stack-file []) {:db {:password "s3cret"}})
            text (compoje.render/render stack-file ctx {})
            [_sent objects] (cli/prepare-stack ctx text)]
        (is (= [["db_password" "s3cret"]] (map (juxt :key :data) objects)))))))

(deftest convert-reverts-unlocatable-edits
  (testing "flow-style definitions can't be edited by line; reported, text unchanged"
    (let [text (str "x-compoje:\n  secrets:\n    - name: s\n      provider: f\n      local-path: g/pw\n"
                    "      spit-key: pw\n"
                    "secrets: {pw: {file: g/pw}}\n")
          {:keys [converted skipped] new-text :text} (ms/convert text)]
      (is (empty? converted))
      (is (re-find #"could not locate" (:reason (first skipped))))
      (is (= text new-text)))))

(deftest migrate-dry-run-local-path
  (let [dir (copy-fixture "local-path")
        template-file (fs/file dir "stack.tpl.yml")
        original (slurp template-file)
        result (migrate/migrate! dir {:dry-run true})]
    (is (:changed result))
    (is (str/includes? (:text result) "from: secrets.app.db_password"))
    (is (= original (slurp template-file)) "dry-run writes nothing")))
