(ns compoje.init
  "`compoje init <dir>`: scaffold a new stack directory from the templates in
   resources/compoje/init. The templates are copied as they are (their
   <{ }> tags are for `compoje deploy`); only @STACK@ is replaced."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def files
  "[target-path resource-path]; .gitignore is stored without the dot."
  [["stack.tpl.yml" "compoje/init/stack.tpl.yml"]
   ["html/index.html" "compoje/init/html/index.html"]
   [".gitignore" "compoje/init/gitignore"]])

(defn stack-name
  "A valid stack name from a directory name: lower case, letters, digits, - and _,
   starting with a letter or digit."
  [dir-name]
  (let [n (-> (str dir-name) str/lower-case
              (str/replace #"[^a-z0-9_-]+" "-")
              (str/replace #"^[^a-z0-9]+" "")
              (str/replace #"-+$" ""))]
    (when (seq n) n)))

^:rct/test
(comment
  (stack-name "hello") ;=> "hello"
  (stack-name "My Web.App") ;=> "my-web-app"
  (stack-name "_tmp-") ;=> "tmp"
  (stack-name "...") ;=> nil
  )

(defn valid-stack-name? [s]
  (boolean (and s (re-matches #"[a-z0-9][a-z0-9_-]*" s))))

(defn scaffold
  "Scaffold file contents for stack `stack`: {target-path text}."
  [stack]
  (into {} (for [[target resource] files]
             [target (str/replace (slurp (io/resource resource)) "@STACK@" stack)])))

(defn init!
  "Create the stack directory `dir`. Refuses to overwrite an existing
   stack.tpl.yml unless :force; other existing files (e.g. your .gitignore)
   are kept, or overwritten with :force (except .gitignore, always kept).
   Returns [{:path :action}] with :action :created, :overwritten or :kept."
  [dir {:keys [stack force]}]
  (let [stack (or stack (stack-name (fs/file-name (fs/absolutize dir))))
        stack-file (fs/path dir "stack.tpl.yml")]
    (when-not (valid-stack-name? stack)
      (throw (ex-info (str "Invalid stack name: " (pr-str stack)
                           ". Use lower case letters, digits, - and _; set it with --stack.")
                      {:stack stack})))
    (when (and (fs/exists? stack-file) (not force))
      (throw (ex-info (str stack-file " already exists. Use --force to overwrite it.") {:file (str stack-file)})))
    (fs/create-dirs dir)
    (vec (for [[target text] (scaffold stack)
               :let [path (fs/path dir target)
                     exists? (fs/exists? path)
                     keep? (and exists? (or (not force) (= ".gitignore" target)))]]
           (do (when-not keep?
                 (fs/create-dirs (fs/parent path))
                 (spit (str path) text))
               {:path (str path)
                :action (cond keep? :kept exists? :overwritten :else :created)})))))
