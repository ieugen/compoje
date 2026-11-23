#!/usr/bin/env bb
;; compoje installer: downloads compoje.jar from a GitHub release, checks its
;; SHA-256, and installs a `compoje` command. Needs only Babashka.
;;
;; One line (latest release):
;;   bb -e '(load-string (slurp "https://raw.githubusercontent.com/ieugen/compoje/main/install.clj"))'
;; With options, after the expression:
;;   bb -e '(load-string (slurp "https://raw.githubusercontent.com/ieugen/compoje/main/install.clj"))' --version v0.6.1
;; Or from a checkout: bb install.clj [options]
;;
;; Run it again to update. See --help and docs/install.md.
(ns install
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def repo "ieugen/compoje")
(def marker "# compoje launcher, written by compoje's install.clj")

(def usage
  "Install compoje (https://github.com/ieugen/compoje) as a `compoje` command.

Options:
  --version TAG   release to install, e.g. v0.6.1 (default: latest; env COMPOJE_VERSION)
  --dir DIR       where to put the `compoje` command (default: ~/.local/bin; env COMPOJE_INSTALL_DIR)
  --jar-dir DIR   where to keep the jar (default: ~/.local/share/compoje)
  --from PATH|URL install this jar instead of a release download (checked against PATH.sha256 if present)
  --completion-dir DIR  where to put the bash completion (default: ~/.local/share/bash-completion/completions)
  --no-completion install no shell completion
  --force         replace a `compoje` command that this installer did not write
  --uninstall     remove the command and the jars
  --help          show this help")

(defn parse-args
  "Options map from command line args; flags without a value are true."
  [args env]
  (loop [[a & more] args
         opts {:version (not-empty (get env "COMPOJE_VERSION"))
               :dir (not-empty (get env "COMPOJE_INSTALL_DIR"))}]
    (cond
      (nil? a) opts
      (#{"--force" "--uninstall" "--no-completion" "--help" "-h"} a)
      (recur more (assoc opts (keyword (str/replace a #"^-+" "")) true))
      (#{"--version" "--dir" "--jar-dir" "--from" "--completion-dir"} a)
      (if (and (seq more) (not (str/starts-with? (first more) "--")))
        (recur (rest more) (assoc opts (keyword (subs a 2)) (first more)))
        (throw (ex-info (str a " needs a value") {})))
      :else (throw (ex-info (str "Unknown option: " a "\n\n" usage) {})))))

(defn sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn expected-sha256
  "The hash from a sha256sum line (\"<hex>  compoje.jar\")."
  [text]
  (some->> text str/trim not-empty (re-find #"^[0-9a-fA-F]{64}") str/lower-case))

(defn launcher
  "The `compoje` command: runs the jar with bb."
  [jar]
  (str "#!/bin/sh\n" marker "\nexec bb \"" jar "\" \"$@\"\n"))

(defn on-path? [dir path-env]
  (let [d (str (fs/normalize (fs/absolutize dir)))]
    (some #(= d (str (fs/normalize (fs/absolutize %)))) (remove str/blank? (str/split (str path-env) #":")))))

(defn- fetch-bytes
  "Bytes of an https URL (redirects followed) or a local file; nil on 404."
  [source]
  (if (re-find #"^https?://" (str source))
    (let [{:keys [status body]} (http/get source {:as :bytes :throw false :follow-redirects :normal})]
      (cond (= 200 status) body
            (= 404 status) nil
            :else (throw (ex-info (str "Download failed (" status "): " source) {}))))
    (when (fs/exists? source) (fs/read-all-bytes source))))

(defn latest-version []
  (let [{:keys [status body]} (http/get (str "https://api.github.com/repos/" repo "/releases/latest")
                                        {:headers {"Accept" "application/vnd.github+json"} :throw false})]
    (if (= 200 status)
      (get (json/parse-string body) "tag_name")
      (throw (ex-info (str "Could not find the latest compoje release (GitHub answered " status ")."
                           " Pass --version vX.Y.Z, see https://github.com/" repo "/releases")
                      {})))))

(defn- download [{:keys [version from]}]
  (let [[jar-src sha-src label]
        (if from
          [from (str from ".sha256") from]
          (let [v (or version (latest-version))
                base (str "https://github.com/" repo "/releases/download/" v "/")]
            [(str base "compoje.jar") (str base "compoje.jar.sha256") v]))
        jar (or (fetch-bytes jar-src)
                (throw (ex-info (str "Not found: " jar-src) {})))
        expected (some-> (fetch-bytes sha-src) (String. "UTF-8") expected-sha256)]
    (cond
      (and expected (not= expected (sha256-hex jar)))
      (throw (ex-info (str "Checksum mismatch for " jar-src ": expected " expected ", got " (sha256-hex jar)) {}))
      expected (println "Checksum OK" (subs expected 0 12))
      (not from) (throw (ex-info (str "No checksum file " sha-src "; refusing to install an unchecked jar.") {}))
      :else (println "Note: no" sha-src "- jar not checked."))
    {:bytes jar :label label}))

(defn- dirs [{:keys [dir jar-dir completion-dir]}]
  (let [home (System/getProperty "user.home")
        data (or (not-empty (System/getenv "XDG_DATA_HOME")) (str (fs/path home ".local" "share")))]
    {:bin (str (or dir (fs/path home ".local" "bin")))
     :jars (str (or jar-dir (fs/path home ".local" "share" "compoje")))
     ;; bash-completion loads <dir>/compoje on the first `compoje <Tab>`
     :completions (str (or completion-dir (fs/path data "bash-completion" "completions")))}))

(def completion-marker "# compoje shell completion")

(defn- install-completion!
  "Write the bash completion for the installed command. Best effort: a jar
   without the `completion` command only gets a note."
  [cmd completions]
  (let [{:keys [out exit]} (p/shell {:out :string :err :string :in "" :continue true} (str cmd) "completion" "bash")
        f (fs/path completions "compoje")]
    (if (and (zero? exit) (str/starts-with? out completion-marker))
      (do (fs/create-dirs completions)
          (spit (str f) out)
          (println "Bash completion ->" (str f) "(open a new shell)")
          ;; bash-completion loads this file only for the `compoje` command;
          ;; `bb compoje ...` needs it loaded at shell start
          (let [rc (fs/path (fs/home) ".bashrc")]
            (when-not (and (fs/exists? rc) (str/includes? (slurp (str rc)) (str f)))
              (println (str "For `bb compoje ...` completion too, add to ~/.bashrc: source " f)))))
      (println "Note: no shell completion installed (this compoje has no `completion` command)."))))

(defn- ours? [f] (and (fs/exists? f) (str/includes? (slurp (str f)) marker)))

(defn uninstall! [opts]
  (let [{:keys [bin jars]} (dirs opts)
        cmd (fs/path bin "compoje")]
    (when (fs/exists? cmd)
      (if (ours? cmd)
        (do (fs/delete cmd) (println "Removed" (str cmd)))
        (println "Left" (str cmd) "alone: not written by this installer.")))
    (when (fs/exists? jars)
      (fs/delete-tree jars)
      (println "Removed" jars))
    (let [f (fs/path (:completions (dirs opts)) "compoje")]
      (when (and (fs/exists? f) (str/starts-with? (slurp (str f)) completion-marker))
        (fs/delete f)
        (println "Removed" (str f))))))

(defn install! [opts]
  (when-not (fs/which "bb")
    (throw (ex-info "bb (Babashka) is not on PATH; the compoje command needs it. See https://babashka.org" {})))
  (let [{:keys [bin jars completions]} (dirs opts)
        cmd (fs/path bin "compoje")
        _ (when (and (fs/exists? cmd) (not (ours? cmd)) (not (:force opts)))
            (throw (ex-info (str cmd " exists and was not written by this installer (bbin?)."
                                 " Remove it, use --dir, or pass --force.")
                            {})))
        {:keys [bytes label]} (download opts)
        jar (fs/path jars (str "compoje-" (if (:from opts) "local" label) ".jar"))
        tmp (fs/path jars (str ".compoje-" (random-uuid) ".jar"))]
    (fs/create-dirs jars)
    (fs/create-dirs bin)
    (fs/write-bytes tmp bytes)
    (fs/move tmp jar {:replace-existing true})
    (doseq [old (fs/glob jars "compoje-*.jar") :when (not= (str old) (str jar))]
      (fs/delete old))
    (spit (str cmd) (launcher (str (fs/absolutize jar))))
    (fs/set-posix-file-permissions cmd "rwxr-xr-x")
    ;; empty stdin: a broken jar makes bb start a REPL instead of failing
    (let [{:keys [out exit err]} (p/shell {:out :string :err :string :in "" :continue true} (str cmd) "--version")]
      (when-not (and (zero? exit) (str/starts-with? (str/trim out) "compoje"))
        (throw (ex-info (str "The installed command does not work (" (str/trim (str err out)) "). Is " jar " a compoje jar?") {})))
      (println "Installed" (str/trim out) "->" (str cmd)))
    (when-not (:no-completion opts)
      (install-completion! cmd completions))
    (when-not (on-path? bin (System/getenv "PATH"))
      (println (str "Add " bin " to your PATH, e.g.: echo 'export PATH=\"" bin ":$PATH\"' >> ~/.bashrc")))))

(defn main [args]
  (try
    (let [opts (parse-args args (into {} (System/getenv)))]
      (cond
        (or (:help opts) (:h opts)) (println usage)
        (:uninstall opts) (uninstall! opts)
        :else (install! opts)))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*] (println "compoje install:" (ex-message e)))
      (System/exit 1))))

;; tests load this file with -Dcompoje.install.no-run=true
(when-not (System/getProperty "compoje.install.no-run")
  (main *command-line-args*))
