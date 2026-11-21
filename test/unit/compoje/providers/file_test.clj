(ns unit.compoje.providers.file-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [compoje.providers :as providers]
            [compoje.providers.file :as p]))

(def fixtures (str (fs/absolutize "test-resources/file_provider")))

(def data {:db {:host "db.internal" :port 5432} :password "p@ss: w0rd"})

(defn- run [config secret-spec template-dir]
  (providers/run (p/->FileSecretsProvider)
                 {:config config :secret-spec secret-spec :template-dir template-dir}))

(defn- error-message [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest formats
  (testing "format from the extension"
    (doseq [f ["data.yml" "data.json" "data.edn"]]
      (is (= data (run {} {:name "d" :file f} fixtures)) f))
    (is (= "s3cret-token\n" (run {} {:name "t" :file "token.txt"} fixtures))))
  (testing "explicit format wins"
    (is (= (slurp (fs/file fixtures "data.yml"))
           (run {} {:name "d" :file "data.yml" :format "raw"} fixtures)))
    (is (= {:region "eu"} (run {} {:name "c" :file "shared/common.yml" :format "yaml"} fixtures)))))

(deftest dir-resolution
  (testing "file relative to the provider dir"
    (is (= {:region "eu"} (run {:dir "shared"} {:name "c" :file "common.yml"} fixtures))))
  (testing "absolute file ignores dir"
    (is (= data (run {:dir "shared"} {:name "d" :file (str (fs/file fixtures "data.json"))} fixtures)))))

(deftest local-path
  (let [dir (fs/create-temp-dir {:prefix "compoje-file-"})]
    (try
      (doseq [f ["data.yml" "token.txt"]]
        (fs/copy (fs/file fixtures f) (fs/file (str dir) f)))
      (testing "spit-key writes one value"
        (run {} {:name "d" :file "data.yml" :local-path "out/pw.txt" :spit-key "password"} (str dir))
        (is (= "p@ss: w0rd" (slurp (fs/file (str dir) "out/pw.txt")))))
      (testing "without spit-key the file is copied unchanged"
        (run {} {:name "d" :file "data.yml" :local-path "out/data.yml"} (str dir))
        (is (= (slurp (fs/file fixtures "data.yml")) (slurp (fs/file (str dir) "out/data.yml"))))
        (run {} {:name "t" :file "token.txt" :local-path "out/token.txt"} (str dir))
        (is (= "s3cret-token\n" (slurp (fs/file (str dir) "out/token.txt")))))
      (finally (fs/delete-tree dir)))))

(deftest errors
  (is (re-find #"secret x: file not found: .*nope\.yml"
               (error-message #(run {} {:name "x" :file "nope.yml"} fixtures))))
  (is (re-find #"secret x: missing file"
               (error-message #(run {} {:name "x"} fixtures))))
  (is (re-find #"Unknown file format: toml"
               (error-message #(run {} {:name "x" :file "data.yml" :format "toml"} fixtures))))
  (is (re-find #"secret x: cannot read .*token\.txt as json"
               (error-message #(run {} {:name "x" :file "token.txt" :format "json"} fixtures)))))

(deftest provide-secrets-test
  (providers/register-provider! (p/->FileSecretsProvider))
  (is (= {:common {:region "eu"} :token "s3cret-token\n"}
         (providers/provide-secrets
          {:template-dir fixtures
           :providers [{:name "files" :type "compoje.providers.file/file"}]
           :secrets [{:name "common" :provider "files" :file "shared/common.yml"}
                     {:name "token" :provider "files" :file "token.txt"}]}))))
