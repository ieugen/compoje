(ns unit.compoje.completion-test
  "Shell completion: the model follows cli/cmdspec, and the generated bash
   script completes correctly when run by bash (with and without the
   bash-completion package)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [compoje.cli :as cli]
            [compoje.completion :as c]))

(def bash-completion "/usr/share/bash-completion/bash_completion")

(deftest model-follows-the-cli
  (let [{:keys [global commands]} (c/model cli/cmdspec)
        by-name (into {} (map (juxt :name identity)) commands)]
    (is (= ["deploy" "render" "migrate" "init" "completion"] (map :name commands)))
    (is (= {"deploy" :stack-file "render" :stack-file "migrate" :dir "init" :dir "completion" :shell}
           (into {} (map (juxt :name :positional)) commands)))
    (testing "every flag of every command is known, with its argument"
      (is (some #{"--deploy-driver"} (mapcat :names global)))
      (is (= #{"--stack" "-s" "--set" "--context" "--resolve-image"}
             (set (mapcat :names (filter :arg? (:flags (by-name "deploy")))))))
      (is (some #{"--no-with-registry-auth"} (mapcat :names (:flags (by-name "deploy"))))))
    (testing "flags with value lists exist in the CLI"
      (is (every? (set (mapcat :names (concat global (mapcat :flags commands)))) (keys c/flag-values))))))

(defn- complete
  "Run the generated script in bash: complete `words` (last is the word being
   completed) in `dir`. Returns COMPREPLY as a vector."
  [{:keys [dir env with-bash-completion prelude]} & words]
  (let [script (str (fs/create-temp-file {:suffix ".bash"}))
        driver (str (when with-bash-completion (str "source " bash-completion "\n"))
                    "source " script "\n"
                    (or prelude "")
                    "COMP_WORDS=(" (str/join " " (map #(str "'" % "'") words)) ")\n"
                    "COMP_CWORD=" (dec (count words)) "\n"
                    "COMP_LINE=\"${COMP_WORDS[*]}\"; COMP_POINT=${#COMP_LINE}\n"
                    (if (= "bb" (first words)) "_compoje_bb" "_compoje")
                    " \"${COMP_WORDS[0]}\" \"${COMP_WORDS[COMP_CWORD]}\" \"${COMP_WORDS[COMP_CWORD-1]}\"\n"
                    "printf '%s\\n' \"${COMPREPLY[@]}\"\n")]
    (try
      (spit script (c/bash-script (c/model cli/cmdspec)))
      (->> (p/shell {:dir (str dir) :out :string :err :string :extra-env (or env {})} "bash" "-c" driver)
           :out str/split-lines (remove str/blank?) vec)
      (finally (fs/delete script)))))

(defn- run-cases [opts]
  (let [dir (fs/create-temp-dir)
        docker (fs/path (fs/create-temp-dir) "docker-config")
        opts (assoc opts :dir dir :env {"DOCKER_CONFIG" (str docker)})]
    (try
      (doseq [f ["web.yml" "db.yaml" "notes.txt"]] (spit (str (fs/path dir f)) ""))
      (fs/create-dirs (fs/path dir "stacks"))
      (doseq [n ["prod-swarm" "staging"]]
        (let [meta-dir (fs/path docker "contexts" "meta" n)]
          (fs/create-dirs meta-dir)
          ;; docker writes meta.json without a trailing newline
          (spit (str (fs/path meta-dir "meta.json")) (str "{\"Name\":\"" n "\",\"Endpoints\":{}}"))))
      (testing "commands"
        (is (= ["deploy" "render" "migrate" "init" "completion"] (complete opts "compoje" "")))
        (is (= ["deploy"] (complete opts "compoje" "de"))))
      (testing "flags: global and per command"
        (is (= ["--resolve-image"] (complete opts "compoje" "deploy" "--re")))
        (is (= ["--force"] (complete opts "compoje" "init" "--f")))
        (is (some #{"--deploy-driver"} (complete opts "compoje" "-"))))
      (testing "flag values"
        (is (= ["always" "changed" "never"] (complete opts "compoje" "deploy" "--resolve-image" "")))
        (is (= ["docker-cli" "docker-api" "docker"] (complete opts "compoje" "--deploy-driver" "")))
        (is (= ["docker.stack"] (complete opts "compoje" "deploy" "-s" "docker.st")))
        (is (= ["default" "prod-swarm" "staging"] (sort (complete opts "compoje" "deploy" "--context" "")))))
      (testing "a flag's argument is not taken for the command"
        (is (= ["deploy"] (complete opts "compoje" "--deploy-driver" "docker-api" "de"))))
      (testing "positionals: stack files (yml/yaml + dirs), dirs, shells"
        (is (= ["db.yaml" "stacks" "web.yml"] (sort (complete opts "compoje" "deploy" ""))))
        (is (= ["stacks"] (complete opts "compoje" "init" "")))
        (is (= ["bash" "zsh"] (complete opts "compoje" "completion" ""))))
      (testing "nothing after the positional"
        (is (= [] (complete opts "compoje" "deploy" "web.yml" ""))))
      (testing "bb compoje ... is completed; other bb words go to bb's completion"
        (is (= ["changed"] (complete opts "bb" "compoje" "deploy" "--resolve-image" "c")))
        (is (= ["some-task"] (complete (assoc opts :prelude "__compoje_bb_prev=_fake_bb\n_fake_bb() { COMPREPLY=(some-task); }\n")
                                       "bb" "t"))))
      (finally (fs/delete-tree dir) (fs/delete-tree (fs/parent docker))))))

(deftest bash-completion-plain
  (if (fs/which "bash")
    (run-cases {})
    (is true "no bash")))

(deftest bash-completion-with-bash-completion-package
  (if (and (fs/which "bash") (fs/exists? bash-completion))
    (run-cases {:with-bash-completion true})
    (is true "bash-completion not installed")))

(deftest zsh-script
  (let [script (c/zsh-script (c/model cli/cmdspec))]
    (is (str/starts-with? script "# compoje zsh completion"))
    (is (str/includes? script "bashcompinit"))
    (if (fs/which "zsh")
      (let [f (str (fs/create-temp-file {:suffix ".zsh"}))]
        (spit f script)
        (is (str/includes? (:out (p/shell {:out :string :err :string}
                                          "zsh" "-c" (str "autoload -U compinit && compinit -u; source " f
                                                          "; complete -p compoje 2>/dev/null || echo registered")))
                           "compoje")))
      (is true "zsh not installed"))))
