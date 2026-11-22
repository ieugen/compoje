(ns unit.compoje.init-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [compoje.cli :as cli]
            [compoje.init :as init]
            [compoje.render :as render]))

(defn- temp-dir [] (str (fs/create-temp-dir {:prefix "compoje-init-"})))

(deftest scaffolded-stack-renders
  (let [dir (str (fs/path (temp-dir) "hello"))
        result (init/init! dir {})]
    (testing "files created"
      (is (= [:created :created :created] (map :action result)))
      (is (every? #(fs/exists? (fs/path dir %)) ["stack.tpl.yml" "html/index.html" ".gitignore"])))
    (testing "stack name from the directory; template tags copied, not rendered"
      (let [text (slurp (str (fs/path dir "stack.tpl.yml")))]
        (is (str/includes? text "stack: hello"))
        (is (str/includes? text "<{ values.port }>"))))
    (testing "the stack renders, and the page becomes an in-memory config"
      (let [stack-file (str (fs/path dir "stack.tpl.yml"))
            ctx (cli/deploy-context {:stack-file stack-file})
            text (render/render stack-file ctx {})
            [_ objects parsed] (cli/prepare-stack ctx text)]
        (is (= "hello" (get-in ctx [:docker :stack])))
        (is (str/includes? text "\"8080:80\""))
        (is (= [[:config "index" "<h1>Hello from compoje</h1>\n"]] (map (juxt :kind :key :data) objects)))
        (is (re-matches #"hello_index-[0-9a-f]{10}" (:name (first objects))))
        (is (= {"external" true "name" (:name (first objects))} (get-in parsed ["configs" "index"])))))))

(deftest existing-files
  (let [dir (temp-dir)]
    (spit (str (fs/path dir ".gitignore")) "mine\n")
    (init/init! dir {:stack "s1"})
    (testing "an existing .gitignore is kept"
      (is (= "mine\n" (slurp (str (fs/path dir ".gitignore"))))))
    (testing "an existing stack.tpl.yml is refused without force"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exists. Use --force"
                            (init/init! dir {:stack "s2"}))))
    (testing "force overwrites the stack, still keeps .gitignore"
      (is (= {"stack.tpl.yml" :overwritten "index.html" :overwritten ".gitignore" :kept}
             (into {} (map (juxt #(fs/file-name (:path %)) :action) (init/init! dir {:stack "s2" :force true})))))
      (is (str/includes? (slurp (str (fs/path dir "stack.tpl.yml"))) "stack: s2"))
      (is (= "mine\n" (slurp (str (fs/path dir ".gitignore"))))))))

(deftest stack-names
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid stack name: \"Bad!\""
                        (init/init! (temp-dir) {:stack "Bad!"})))
  (is (str/includes? (slurp (str (fs/path (doto (str (fs/path (temp-dir) "My Web.App")) (init/init! {})) "stack.tpl.yml")))
                     "stack: my-web-app")))
