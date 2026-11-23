(ns compoje.files
  "Files that a run would write, kept in memory on --dry-run.

   Providers with the legacy local-path write decrypted secrets to disk, and
   templates often file-hash those files. On a dry run nothing may be written,
   so `compoje.providers/secret->file!` stores the content here instead, and
   everything that reads such files (file-hash, hash-file, the docker-api
   engine's file: secrets/configs) reads through `read-file` / `read-bytes`."
  (:require [babashka.fs :as fs]))

(def ^:dynamic *virtual-files*
  "nil (write to disk), or an atom {absolute-path content} on a dry run."
  nil)

(defn path-key
  "The key of `path` in *virtual-files*: absolute and normalized."
  [path]
  (str (fs/normalize (fs/absolutize (fs/path (str path))))))

(defn virtual?
  "Are files kept in memory (a dry run)?"
  []
  (some? *virtual-files*))

(defn write!
  "Keep `content` for `path` in memory. Only on a dry run."
  [path content]
  (swap! *virtual-files* assoc (path-key path) (str content)))

(defn- virtual-content [path]
  (when *virtual-files* (get @*virtual-files* (path-key path))))

(defn read-file
  "The text of `path`: the dry-run content if there is one, else the file."
  [path]
  (or (virtual-content path) (slurp (str path))))

(defn read-bytes
  "The bytes of `path`: the dry-run content if there is one, else the file."
  ^bytes [path]
  (if-let [c (virtual-content path)]
    (.getBytes ^String c "UTF-8")
    (fs/read-all-bytes (fs/path (str path)))))

^:rct/test
(comment
  (binding [*virtual-files* (atom {})]
    (write! "/tmp/compoje-rct/../compoje-rct-x/pw.txt" "s3cret")
    [(virtual?) (read-file "/tmp/compoje-rct-x/pw.txt") (String. (read-bytes "/tmp/compoje-rct-x/./pw.txt") "UTF-8")])
  ;=> [true "s3cret" "s3cret"]
  (virtual?) ;=> false
  )
