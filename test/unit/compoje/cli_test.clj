(ns unit.compoje.cli-test
  (:require [babashka.fs :as fs]
            [cheshire.core]
            [clojure.string :as str]
            [compoje.render]
            [clojure.test :refer [deftest is testing]]
            [compoje.cli :as cli]
            [compoje.deploy.docker :as docker]
            [lambdaisland.cli :as licli]
            [lambdaisland.cli.cmdspec :as cmdspec]))

(def nginx "example-stacks/nginx/stack.tpl.yml")

(defn- parse
  "Run the real cmdspec with deploy stubbed out; return the opts deploy receives."
  [& args]
  (with-redefs [cli/deploy (fn [opts] (dissoc opts ::licli/sources ::licli/argv ::licli/command))
                cli/migrate (fn [opts] (assoc (dissoc opts ::licli/sources ::licli/argv ::licli/command)
                                              :command :migrate))]
    (licli/dispatch* (cmdspec/to-cmdspec cli/cmdspec) (vec args))))

(deftest parse-test
  (testing "global and deploy flags"
    (is (= {:verbosity 2
            :stack-file "f.yml"
            :dry-run true
            :prune true
            :stack "s1"
            :context "ctx"
            :resolve-image "never"
            :with-registry-auth false
            :set ["a.b=1" "c=2"]}
           (parse "-vv" "deploy" "--dry-run" "--prune" "--stack" "s1" "--context" "ctx"
                  "--resolve-image" "never" "--no-with-registry-auth"
                  "-s" "a.b=1" "--set" "c=2" "f.yml"))))

  (testing "defaults"
    (is (= {:verbosity 0 :stack-file "f.yml"}
           (parse "deploy" "f.yml"))))

  (testing "migrate"
    (is (= {:command :migrate :verbosity 0 :dir "stacks/nginx" :dry-run true}
           (parse "migrate" "--dry-run" "stacks/nginx"))))

  (testing "unknown flags are rejected (strict)"
    (is (thrown-with-msg? Exception #"Unknown flag: --nope"
                          (parse "deploy" "--nope" "f.yml"))))

  (testing "missing stack file does not call deploy"
    (let [result (atom :not-set)
          out (with-out-str (reset! result (parse "deploy")))]
      (is (nil? @result))
      (is (re-find #"Positional arguments missing: <stack-file>" out)))))

(deftest version-test
  (testing "version comes from resources/compoje/VERSION"
    (is (re-matches #"\d+\.\d+\.\d+(-SNAPSHOT)?" (cli/version))))
  (testing "--version prints it and does not dispatch"
    (let [result (atom nil)
          out (with-out-str (reset! result (cli/main "--version")))]
      (is (= ::cli/done @result))
      (is (= (str "compoje " (cli/version) "\n") out)))))

(deftest deploy-context-test
  (testing "x-compoje docker config reaches the context, with defaults"
    (is (= {:stack "nginx"
            :resolve-image "always"
            :with-registry-auth true
            :prune true}
           (:docker (cli/deploy-context {:stack-file nginx})))))

  (testing "precedence: defaults < x-compoje < --set < CLI flags"
    (let [docker (:docker (cli/deploy-context {:stack-file nginx
                                               :set ["docker.stack=from-set"
                                                     "docker.context=set-ctx"]
                                               :stack "from-flag"
                                               :with-registry-auth false
                                               :prune false}))]
      (is (= "from-flag" (:stack docker)))
      (is (= "set-ctx" (:context docker)))
      (is (false? (:prune docker)) "--no-prune overrides the default")
      (is (not (:with-registry-auth docker)))))

  (testing "the docker command has the stack name"
    (let [docker (:docker (cli/deploy-context {:stack-file nginx}))]
      (is (= ["docker" "stack" "deploy" "--with-registry-auth" "--resolve-image" "always"
              "--prune" "--compose-file" "-" "nginx"]
             (docker/deploy-args docker))))))

(deftest deploy-args-test
  (is (= ["docker" "--context" "remote" "stack" "deploy" "--resolve-image" "never" "--prune"
          "--compose-file" "-" "web"]
         (docker/deploy-args {:context "remote" :stack "web" :resolve-image "never"
                              :prune true :with-registry-auth false}))))

(deftest render-test
  (testing "render prints the rendered stack to stdout"
    (let [out (with-out-str (is (= ::cli/done (cli/render {:stack-file nginx :verbosity 0}))))]
      (is (re-find #"(?m)^services:" out))
      (is (re-find #"name: nginx_cfg-[0-9a-f]{6}" out))))
  (testing "--output writes a file"
    (let [f (str (fs/create-temp-file {:suffix ".yml"}))]
      (cli/render {:stack-file nginx :verbosity 0 :output f})
      (is (re-find #"(?m)^services:" (slurp f)))
      (fs/delete f))))

(deftest compose-file-ignored-test
  (is (nil? (get-in (cli/deploy-context {:stack-file nginx :set ["docker.compose-file=/tmp/x.yml"]})
                    [:docker :compose-file]))))

(deftest prepare-stack-test
  (cli/register-default-providers!)
  (testing "stacks without x-compoje definitions pass through unchanged"
    (is (= ["services: {}\n" [] nil] (cli/prepare-stack {} "services: {}\n"))))
  (testing "managed secrets and configs are created by compoje and externalized"
    (let [stack-file "example-stacks/file/stack.tpl.yml"
          ctx (cli/deploy-context {:stack-file stack-file})
          [_ctx contents] [ctx (compoje.render/render stack-file ctx {})]
          [text objects] (cli/prepare-stack ctx contents)
          sent (cheshire.core/parse-string text)]
      (is (= [[:secret "api_token" "demo-only-not-a-real-token\n"]
              [:config "app_conf" true]]
             (map (fn [{:keys [kind key data]}]
                    [kind key (if (= :config kind) (str/includes? data "region = eu-central") data)])
                  objects)))
      (is (every? #(re-matches #"file-example_(api_token|app_conf)-[0-9a-f]{10}" (:name %)) objects))
      (is (= {"external" true "name" (:name (first objects))} (get-in sent ["secrets" "api_token"])))
      (is (= {"external" true "name" (:name (second objects))} (get-in sent ["configs" "app_conf"])))
      (testing "the secret value is not in what docker gets"
        (is (not (str/includes? text "demo-only-not-a-real-token")))))))

(deftest object-create-args-test
  (is (= ["docker" "--context" "c" "secret" "create" "--label" "a=1" "--label" "b=2" "pw-1" "-"]
         (docker/object-create-args {:context "c"} {:kind :secret :name "pw-1" :labels {"b" "2" "a" "1"}})))
  (is (= ["docker" "config" "create" "c-1" "-"]
         (docker/object-create-args {} {:kind :config :name "c-1" :labels {}}))))

(deftest config-validation
  (let [dir (fs/create-temp-dir)
        stack (fs/path dir "stack.tpl.yml")]
    (try
      (testing "errors are listed together, with did-you-mean"
        (spit (str stack) "x-compoje:\n  docker:\n    stak: web\n    resolve-image: sometimes\nservices: {}\n")
        (let [msg (try (cli/cli-config->context (str stack) []) nil
                       (catch clojure.lang.ExceptionInfo e (ex-message e)))]
          (is (str/includes? msg "Invalid x-compoje configuration"))
          (is (str/includes? msg "docker.stak: unknown key; did you mean stack?"))
          (is (str/includes? msg "docker.resolve-image: should be either"))))
      (testing "--set strings become booleans: --set docker.prune=false does not prune"
        (spit (str stack) "x-compoje:\n  docker:\n    stack: web\nservices: {}\n")
        (let [docker (:docker (cli/cli-config->context (str stack) ["docker.prune=false"]))]
          (is (false? (:prune docker)))
          (is (not (some #{"--prune"} (docker/deploy-args docker))))))
      (testing "unknown top-level keys only warn and are kept"
        (spit (str stack) "x-compoje:\n  docker:\n    stack: web\n  extra:\n    a: 1\nservices: {}\n")
        (is (= {:a 1} (:extra (cli/cli-config->context (str stack) [])))))
      (finally (fs/delete-tree dir)))))

(deftest renders-the-passed-file
  (testing "a stack file with another name is rendered itself (no stack.tpl.yml next to it)"
    (let [dir (fs/create-temp-dir)
          f (fs/path dir "web.yml")]
      (try
        (spit (str f) "x-compoje:\n  docker:\n    stack: web\n  values:\n    n: 3\nservices:\n  a:\n    image: i\n    deploy:\n      replicas: <{ values.n }>\n")
        (is (str/includes? (with-out-str (cli/render {:stack-file (str f) :verbosity 0})) "replicas: 3"))
        (finally (fs/delete-tree dir))))))

(deftest provider-results-under-secrets
  (cli/register-default-providers!)
  (let [dir (fs/create-temp-dir)
        stack (fs/path dir "stack.tpl.yml")
        write! (fn [env] (spit (str stack) (str "x-compoje:\n  docker:\n    stack: web\n"
                                                "  providers:\n    - name: files\n      type: compoje.providers.file/file\n"
                                                "  secrets:\n    - name: cloud\n      provider: files\n      file: cloud.yml\n"
                                                "services:\n  a:\n    image: i\n    environment:\n" env)))]
    (spit (str (fs/path dir "cloud.yml")) "JWT_SECRET: s3cret\n")
    (try
      (testing "secrets.<name>.<key> renders the provider result (the old compoje.edn layout)"
        (write! "      JWT: <{ secrets.cloud.JWT_SECRET|yaml-str }>\n")
        (is (str/includes? (with-out-str (cli/render {:stack-file (str stack) :verbosity 0})) "JWT: \"s3cret\"")))
      (testing "an undefined secrets path is an error, not an empty value"
        (write! "      JWT: <{ secrets.cloud.NOPE }>\n")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"secrets.cloud.NOPE: no such provider result \(secrets: cloud\)"
                              (cli/render {:stack-file (str stack) :verbosity 0}))))
      (testing "a top-level reference to a secret is an error with a hint"
        (write! "      JWT: <{ cloud.JWT_SECRET }>\n")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cloud.JWT_SECRET: provider results are under secrets; write secrets.cloud.JWT_SECRET"
                              (cli/render {:stack-file (str stack) :verbosity 0}))))
      (testing "other undefined variables are warnings; |default is fine"
        (let [ctx (cli/deploy-context {:stack-file (str stack)})]
          (is (= [(str stack ": template variable values.nope is not defined; it renders empty (add |default:\"...\" if that is intended)")]
                 (cli/check-variables (str stack) "<{ values.nope }> <{ values.x|default:\"1\" }>" ctx)))))
      (finally (fs/delete-tree dir)))))
