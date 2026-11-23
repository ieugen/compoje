(ns unit.compoje.migrate-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [compoje.cli :as cli]
            [compoje.config :as config]
            [compoje.context :as context]
            [compoje.migrate :as migrate]
            [compoje.migrate-secrets :as ms]
            [compoje.providers :as providers]
            [compoje.render]
            [compoje.stack :as stack]
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
   ;; local-path/spit-key: removed, nothing in the stack used the file
   :secrets [{:name "cert"
              :provider "my-vault"
              :mount-path "secret"
              :secret-path "nginx"}]
   :values {:resource-reservations-mem "128m"
            :replicas 1
            :cpus "4"
            :features ["gzip"]}})

(deftest migrate-nginx
  (let [dir (copy-fixture "nginx")
        template-file (str (fs/file dir "stack.tpl.yml"))
        original (slurp template-file)
        result (migrate/migrate! dir {})]
    (is (= {:file template-file :changed true :edn true}
           (select-keys result [:file :changed :edn])))
    (testing "config round-trips through x-compoje"
      (is (= expected-config (config/file->config! template-file))))
    (testing "the original template follows the x-compoje section unchanged"
      (is (str/starts-with? (slurp template-file) "x-compoje:\n"))
      (is (str/ends-with? (slurp template-file) (str "\n\n" original))))
    (testing "compoje.edn is kept"
      (is (fs/exists? (fs/file dir "compoje.edn"))))
    (testing "the local-path nothing uses is removed and reported"
      (is (= [{:secret "cert" :local-path "nginx/cert.crt"}] (:removed result))))
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
    (is (= [["nginx" "secrets.nginx" "cert.key"] ["db" "secrets.db.password" nil]]
           (map (juxt :secret :from :key) (:converted result))))
    (is (empty? (:skipped result)))
    (testing "a spit-key with dots becomes from + key (quoted)"
      (is (str/includes? (slurp (str (fs/file dir "stack.tpl.yml")))
                         "  cert_key:\n    x-compoje:\n      from: secrets.nginx\n      key: \"cert.key\"\n")))
    (testing "the migrated stack creates both secrets from memory"
      (let [stack-file (str (fs/file dir "stack.tpl.yml"))
            ctx (context/final-context (cli/cli-config->context stack-file [])
                                       {:db {:password "s3cret"} :nginx {:cert.key "key"}})
            text (compoje.render/render stack-file ctx {})
            [_sent objects] (cli/prepare-stack ctx text)]
        (is (= #{["cert_key" "key"] ["db_password" "s3cret"]} (set (map (juxt :key :data) objects))))))))

(deftest convert-whole-result-with-format
  (let [text (str "x-compoje:\n  secrets:\n  - name: app\n    provider: vault\n    format: edn\n"
                  "    local-path: secrets/config.edn\n    secret-path: app/config\n\n"
                  "secrets:\n  config.edn:\n    file: secrets/config.edn\n"
                  "    name: app_config_<% file-hash 'secrets/config.edn' %>\n")
        {:keys [converted skipped] new-text :text} (ms/convert text)]
    (is (empty? skipped))
    (is (= [["app" "secrets.app" "edn"]] (map (juxt :secret :from :format) converted)))
    (is (= (str "x-compoje:\n  secrets:\n  - name: app\n    provider: vault\n    format: edn\n"
                "    secret-path: app/config\n\n"
                "secrets:\n  config.edn:\n    x-compoje:\n      from: secrets.app\n      format: edn\n")
           new-text))
    (testing "the in-memory secret has the content local-path wrote"
      (let [content {:client_id "id" :client_secret "s3cret"}
            [obj] (stack/managed-objects (yaml/load new-text) {:context {:secrets {:app content}}
                                                               :stack-name "app"})]
        (is (= (providers/secret-content {:content content :format "edn"}) (:data obj)))))))

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

;; ------------------------------------------------------------------ secrets without a name

(def no-name-expected-secret
  "What the janssen-like secret becomes: named, and created from memory."
  {"x-compoje" {"from" "secrets.appsettings" "format" "json"}})

(deftest migrate-secret-without-name
  (testing "from compoje.edn: the secret is named after its local-path, then converted"
    (let [dir (copy-fixture "no-name")
          result (migrate/migrate! dir {})
          data (yaml/load (slurp (str (fs/file dir "stack.tpl.yml"))))]
      (is (= [{:index 0 :name "appsettings"}] (:named result)))
      (is (= ["appsettings"] (map :secret (:converted result))))
      (is (empty? (:skipped result)))
      (is (empty? (:invalid result)))
      (is (= "appsettings" (get-in data ["x-compoje" "secrets" 0 "name"])))
      (is (= no-name-expected-secret (get-in data ["secrets" "appsettings"])))))
  (testing "already x-compoje (compoje.edn deleted): the name is added to the text"
    (let [dir (copy-fixture "no-name")
          template-file (str (fs/file dir "stack.tpl.yml"))]
      (fs/delete (fs/file dir "compoje.edn"))
      (spit template-file (str "x-compoje:\n  docker:\n    stack: janssen\n"
                               "  providers:\n  - name: dre-vault\n    type: compoje.providers.vault/vault\n"
                               "  secrets:\n  - provider: dre-vault   # no name\n    local-path: secrets/appsettings.json\n"
                               "    format: json\n    mount-path: DocLabel\n    secret-path: Janssen/dev/appsettings.json\n\n"
                               (slurp template-file)))
      (let [result (migrate/migrate! dir {})
            text (slurp template-file)]
        (is (= [{:index 0 :name "appsettings"}] (:named result)))
        (is (str/includes? text "  - provider: dre-vault   # no name\n    name: appsettings\n"))
        (is (= no-name-expected-secret (get-in (yaml/load text) ["secrets" "appsettings"])))
        (is (empty? (:invalid result))))))
  (testing "the report names the secret by position, never with an empty name"
    (let [lines (#'cli/migrate-report {:named [{:index 0 :name "appsettings"}] :unnamed [1] :changed true :file "f"} true)]
      (is (some #{"Secret #1 had no name, which the x-compoje format requires; named it appsettings."} lines))
      (is (some #(str/starts-with? % "Secret #2 has no name") lines)))))

(deftest migrate-validates-the-result
  (let [dir (copy-fixture "nginx")]
    (fs/delete (fs/file dir "compoje.edn"))
    (spit (str (fs/file dir "stack.tpl.yml"))
          "x-compoje:\n  docker:\n    stack: s\n  secrets:\n  - name: a\n    provider: nowhere\nservices: {}\n")
    (testing "migrate! lists the validation errors deploy would report"
      (is (= ["secrets[0].provider: unknown provider nowhere (no providers are declared)"]
             (:invalid (migrate/migrate! dir {:dry-run true})))))
    (testing "the command fails (exit 1) instead of returning ::done"
      (is (= ::cli/invalid (cli/migrate {:dir dir :dry-run true :verbosity 0}))))))

;; ------------------------------------------------------------------ local-path nothing uses

(deftest unused-local-path
  (let [head (str "x-compoje:\n  docker:\n    stack: medrag\n"
                  "  providers:\n  - name: v\n    type: compoje.providers.vault/vault\n"
                  "  secrets:\n  - name: medrag\n    provider: v\n    mount-path: DocSearch\n    secret-path: medrag/dev\n"
                  "    # written for the old file: secret\n    local-path: secrets/secrets.json\n    format: json\n")]
    (testing "read only through <{ secrets... }>: local-path (and its comment) is removed"
      (let [{:keys [text removed converted skipped]}
            (ms/convert (str head "services:\n  app:\n    image: i\n    environment:\n      PW: <{ secrets.medrag.pw }>\n"))]
        (is (= [{:secret "medrag" :local-path "secrets/secrets.json"}] removed))
        (is (empty? converted))
        (is (empty? skipped))
        (is (not (str/includes? text "local-path")))
        (is (not (str/includes? text "# written for the old file")))
        (is (str/includes? text "    secret-path: medrag/dev\n    format: json\n"))))
    (testing "used by a bind mount: kept, and the report says where"
      (let [{:keys [text removed skipped]}
            (ms/convert (str head "services:\n  app:\n    image: i\n    volumes:\n      - ./secrets/secrets.json:/app/secrets.json:ro\n"))]
        (is (empty? removed))
        (is (str/includes? text "local-path: secrets/secrets.json"))
        (is (= ["local-path is not used as a Docker secret or config file, but the file is used at line 19; left as is."]
               (map :reason skipped)))))
    (testing "used by an x-compoje template file: kept"
      (let [{:keys [removed skipped]}
            (ms/convert (str head "services: {}\n") {"conf/app.tpl" "include /run/secrets.json\n"})]
        (is (empty? removed))
        (is (str/includes? (:reason (first skipped)) "used at conf/app.tpl"))))))

(deftest migrate-template-that-is-not-yaml
  (let [dir (copy-fixture "nginx")
        template-file (str (fs/file dir "stack.tpl.yml"))]
    ;; like docsearch/monitoring: an Ansible !unsafe tag yamlstar can't read
    (spit template-file "services:\n  a:\n    image: i\n    command: !unsafe '{{ x }}'\n")
    (testing "compoje.edn still moves into x-compoje; the secret steps are skipped and the reason reported"
      (let [result (migrate/migrate! dir {})]
        (is (:edn result))
        (is (:changed result))
        (is (re-find #"(?i)unknown tag.*unsafe" (str (:yaml-error result))))
        (is (empty? (:converted result)))
        (is (str/starts-with? (slurp template-file) "x-compoje:\n"))
        (is (some #(str/starts-with? % "Secrets not migrated: ") (#'cli/migrate-report result false)))))
    (testing "after fixing the template (and deleting compoje.edn), migrate runs the secret steps"
      (fs/delete (fs/file dir "compoje.edn"))
      (spit template-file (str/replace (slurp template-file) "!unsafe " ""))
      (let [result (migrate/migrate! dir {})]
        (is (nil? (:yaml-error result)))
        (is (= [{:secret "cert" :local-path "nginx/cert.crt"}] (:removed result)))))))
