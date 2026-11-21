(ns unit.compoje.cli-test
  (:require [clojure.test :refer [deftest is testing]]
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
            :deploy-driver :docker
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
    (is (= {:verbosity 0 :deploy-driver :docker :stack-file "f.yml"}
           (parse "deploy" "f.yml"))))

  (testing "migrate"
    (is (= {:command :migrate :verbosity 0 :deploy-driver :docker :dir "stacks/nginx" :dry-run true}
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
            :with-registry-auth true}
           (-> (cli/deploy-context {:stack-file nginx})
               :docker
               (dissoc :compose-file)))))

  (testing "precedence: defaults < x-compoje < --set < CLI flags"
    (let [docker (:docker (cli/deploy-context {:stack-file nginx
                                               :set ["docker.stack=from-set"
                                                     "docker.context=set-ctx"]
                                               :stack "from-flag"
                                               :with-registry-auth false
                                               :prune true}))]
      (is (= "from-flag" (:stack docker)))
      (is (= "set-ctx" (:context docker)))
      (is (true? (:prune docker)))
      (is (not (:with-registry-auth docker)))))

  (testing "the docker command has the stack name"
    (let [docker (:docker (cli/deploy-context {:stack-file nginx}))]
      (is (= ["docker" "stack" "deploy" "--with-registry-auth" "--resolve-image" "always"
              "--compose-file" (:compose-file docker) "nginx"]
             (docker/deploy-args docker))))))

(deftest deploy-args-test
  (is (= ["docker" "--context" "remote" "stack" "deploy" "--resolve-image" "never" "--prune"
          "--compose-file" "/tmp/s.yml" "web"]
         (docker/deploy-args {:context "remote" :stack "web" :resolve-image "never"
                              :prune true :with-registry-auth false
                              :compose-file "/tmp/s.yml"}))))
