(ns deps-why
  "`bb deps-why <group/artifact>`: why is a library on the classpath?

   Prints, for the default deps and each deps.edn alias, the dependency paths
   from deps.edn down to the library (from `clojure -X:deps tree`), and the
   jars that embed (shade) a copy of it, found by its
   META-INF/maven/<group>/<artifact>/pom.properties. Vulnerability scanners
   report shaded copies as \"impossible to find in the dependency tree\"."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.util.zip ZipFile)))

(defn- parse-line
  "{:depth :lib :version :status :note} for one `clojure -X:deps tree` line."
  [line]
  (when-let [[_ indent mark lib version note] (re-matches #"( *)(?:([.X]) )?(\S+) (\S+)\s*(.*)" line)]
    {:depth (quot (count indent) 2)
     :lib lib
     :version version
     :status (if (= "X" mark) :excluded :included)
     :note (not-empty (str/trim note))}))

(defn tree-paths
  "Paths from a top-level dependency down to every occurrence of `lib`
   (\"group/artifact\") in `clojure -X:deps tree` output. Each path is a vector
   of parsed lines, root first."
  [tree-text lib]
  (loop [[line & more] (str/split-lines tree-text) stack [] paths []]
    (if (nil? line)
      paths
      (if-let [node (parse-line line)]
        (let [stack (conj (subvec stack 0 (min (:depth node) (count stack))) node)]
          (recur more stack (cond-> paths (= lib (:lib node)) (conj stack))))
        (recur more stack paths)))))

(defn format-path
  "One line: lib version -> lib version ..., with the last node's note."
  [path]
  (let [last-node (peek path)]
    (str (str/join " -> " (map #(str (:lib %) " " (:version %)) path))
         (when (= :excluded (:status last-node))
           (str "  (not used: " (or (:note last-node) "excluded") ")")))))

(defn aliases
  "Alias keywords of a deps.edn map, sorted."
  [deps]
  (sort (keys (:aliases deps))))

;; ------------------------------------------------------------------ side effects

(defn- clojure-out [& args]
  (:out (apply p/shell {:out :string :err :string} "clojure" args)))

(defn- embedded-version
  "Version of `lib` embedded in `jar` (its pom.properties), or nil."
  [jar lib]
  (let [[group artifact] (str/split lib #"/")]
    (with-open [z (ZipFile. (str jar))]
      (when-let [e (.getEntry z (str "META-INF/maven/" group "/" artifact "/pom.properties"))]
        (some #(second (re-matches #"version=(.*)" %))
              (str/split-lines (slurp (.getInputStream z e))))))))

(defn- jar-lib
  "group/artifact of `jar` itself, from its own pom.properties, or nil."
  [jar]
  (with-open [z (ZipFile. (str jar))]
    (let [base (str/replace (fs/file-name jar) #"\.jar$" "")]
      (some (fn [e]
              (when-let [[_ g a] (re-matches #"META-INF/maven/([^/]+)/([^/]+)/pom.properties" (.getName e))]
                (when (str/starts-with? base (str a "-")) (str g "/" a))))
            (enumeration-seq (.entries z))))))

(defn- own-jar? [jar lib]
  (let [artifact (second (str/split lib #"/"))]
    (str/starts-with? (fs/file-name jar) (str artifact "-"))))

(defn run [lib]
  (when-not (and lib (str/includes? lib "/"))
    (println "Usage: bb deps-why <group/artifact>, e.g. bb deps-why org.msgpack/msgpack-core")
    (System/exit 2))
  (let [all (aliases (edn/read-string (slurp "deps.edn")))
        found (atom false)
        trees (into [] (for [alias (cons nil all)]
                         [(or (some-> alias str) "(default)")
                          (apply clojure-out "-X:deps" "tree" (when alias [":aliases" (str "[" alias "]")]))]))
        print-paths (fn [indent l]
                      (doseq [[alias tree] trees
                              path (tree-paths tree l)]
                        (reset! found true)
                        (println (str indent alias "  " (format-path path)))))]
    (println lib)
    (print-paths "  " lib)
    (let [cp (apply clojure-out "-Spath" (when (seq all) [(str "-A" (str/join "" all))]))
          jars (->> (str/split (str/trim cp) #":") (filter #(str/ends-with? % ".jar")) distinct)]
      (doseq [jar jars
              :when (not (own-jar? jar lib))
              :let [v (try (embedded-version jar lib) (catch Exception _ nil))]
              :when v]
        (reset! found true)
        (println "  embedded in" (fs/file-name jar) (str "(version " v ")")
                 (if-let [l (jar-lib jar)] (str "= " l ", which comes from:") ""))
        (some->> (jar-lib jar) (print-paths "      "))))
    (when-not @found
      (println "  not found in any alias, nor embedded in any jar"))))
