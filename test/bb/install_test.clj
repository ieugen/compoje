(ns bb.install-test
  "Tests for install.clj (the one-line installer). Babashka only: the script
   uses babashka.http-client, which the JVM test classpath doesn't have."
  (:require [babashka.fs :as fs]
            [babashka.process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(System/setProperty "compoje.install.no-run" "true")
(load-file "install.clj")
(alias 'i 'install)

(deftest parse-args
  (is (= {:version "v0.3.0" :dir "/opt/bin" :force true}
         (i/parse-args ["--version" "v0.3.0" "--dir" "/opt/bin" "--force"] {})))
  (testing "environment defaults, flags win"
    (is (= {:version "v0.2.0" :dir "/env/bin"}
           (i/parse-args [] {"COMPOJE_VERSION" "v0.2.0" "COMPOJE_INSTALL_DIR" "/env/bin"})))
    (is (= "v9" (:version (i/parse-args ["--version" "v9"] {"COMPOJE_VERSION" "v0.2.0"})))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--dir needs a value" (i/parse-args ["--dir"] {})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown option: --nope" (i/parse-args ["--nope"] {}))))

(deftest checksums
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (i/sha256-hex (.getBytes "abc"))))
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (i/expected-sha256 "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD  compoje.jar\n")))
  (is (nil? (i/expected-sha256 "not a hash"))))

(deftest launcher
  (let [l (i/launcher "/home/u/.local/share/compoje/compoje-v0.3.0.jar")]
    (is (= "#!/bin/sh\n# compoje launcher, written by compoje's install.clj\nexec bb \"/home/u/.local/share/compoje/compoje-v0.3.0.jar\" \"$@\"\n" l))))

(deftest on-path
  (is (i/on-path? "/home/u/.local/bin" "/usr/bin:/home/u/.local/bin/"))
  (is (not (i/on-path? "/home/u/.local/bin" "/usr/bin:/bin"))))

(deftest broken-jar-is-reported-not-hung
  (let [tmp (fs/create-temp-dir)
        jar (fs/path tmp "compoje.jar")]
    (try
      (spit (str jar) "not a jar")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not work"
                            (with-out-str (i/install! {:from (str jar) :dir (str (fs/path tmp "bin"))
                                                       :jar-dir (str (fs/path tmp "share"))}))))
      (finally (fs/delete-tree tmp)))))

(deftest install-and-uninstall-local-jar
  (let [tmp (fs/create-temp-dir)
        jar (fs/path tmp "compoje.jar")
        opts {:from (str jar) :dir (str (fs/path tmp "bin")) :jar-dir (str (fs/path tmp "share"))
              :completion-dir (str (fs/path tmp "completions"))}]
    (try
      ;; a stand-in jar: a tiny bb uberjar whose main prints like compoje --version
      (fs/create-dirs (fs/path tmp "src"))
      (spit (str (fs/path tmp "src" "fake.clj")) "(ns fake) (defn -main [& _] (println \"compoje test\"))")
      (spit (str (fs/path tmp "bb.edn")) "{:paths [\"src\"]}")
      (babashka.process/shell {:dir (str tmp) :out :string :err :string} "bb" "uberjar" "compoje.jar" "-m" "fake")
      (spit (str jar ".sha256") (str (i/sha256-hex (fs/read-all-bytes jar)) "  compoje.jar\n"))
      (testing "a jar without the completion command: a note, no completion file, no failure"
        (is (str/includes? (with-out-str (i/install! opts)) "no shell completion installed"))
        (is (not (fs/exists? (fs/path tmp "completions" "compoje")))))
      (is (= "compoje test\n" (:out (babashka.process/shell {:out :string :in ""} (str (fs/path tmp "bin" "compoje")) "--version"))))
      (with-out-str (i/uninstall! opts))
      (is (not (fs/exists? (fs/path tmp "bin" "compoje"))))
      (is (not (fs/exists? (fs/path tmp "share"))))
      (finally (fs/delete-tree tmp)))))
