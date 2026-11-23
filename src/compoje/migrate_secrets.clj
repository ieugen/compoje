(ns compoje.migrate-secrets
  "Convert provider secrets written to disk (local-path) into Docker secrets
   created from memory (x-compoje: {from: ...}), editing the stack template
   text so comments, layout and Selmer tags survive.

   Before:                                  After:
     x-compoje:                               x-compoje:
       secrets:                                 secrets:
         - name: app                              - name: app
           provider: v                              provider: v
           local-path: generated/pw.txt
           spit-key: db_password
     secrets:                                 secrets:
       db_password:                             db_password:
         file: generated/pw.txt                   x-compoje:
         name: db_password-<% file-hash ... %>      from: secrets.app.db_password

   Every edit is checked: the edited text is parsed again and must equal the
   expected data; otherwise the edit is reverted and reported."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [compoje.providers.file :as file-provider]
            [yamlstar.core :as yaml]))

;; ------------------------------------------------------------------ lines

(defn- indent [^String line] (count (re-find #"^ *" line)))

(defn- ignorable? [line] (boolean (re-matches #"\s*(#.*)?" line)))

(defn- key-pattern [k]
  (re-pattern (str "^ *(?:- +)?[\"']?" (java.util.regex.Pattern/quote (str k)) "[\"']? *:(?: |$).*")))

(defn- key-line?
  "Does `line` hold mapping key `k` at exactly `n` spaces of indentation?"
  [line n k]
  (and (= n (indent line)) (boolean (re-matches (key-pattern k) line))))

(defn block-end
  "End (exclusive) of the block of the key at line `i`: the next content line
   indented no deeper, minus trailing blank/comment lines."
  [lines i]
  (let [line (nth lines i)
        n (indent line)
        ;; a key's block sequence may sit at the key's own indentation:
        ;;   secrets:
        ;;   - name: a
        same-level-items? (not (re-find #"^ *- " line))
        ends? (fn [l] (and (not (ignorable? l))
                           (or (< (indent l) n)
                               (and (= (indent l) n)
                                    (not (and same-level-items? (re-find #"^ *- " l)))))))
        j (or (first (filter #(ends? (nth lines %)) (range (inc i) (count lines))))
              (count lines))]
    (loop [j j]
      (if (and (> j (inc i)) (ignorable? (nth lines (dec j)))) (recur (dec j)) j))))

(defn- child-indent
  "Indentation of the first content line inside the block [i, end)."
  [lines i end]
  (some #(let [l (nth lines %)] (when-not (ignorable? l) (indent l))) (range (inc i) end)))

(defn find-key
  "Line index of key `k` at indentation `n` in [from, to)."
  [lines from to n k]
  (first (filter #(key-line? (nth lines %) n k) (range from to))))

(defn definition-range
  "[start end) of the definition `def-key` under top-level `top-key`."
  [lines top-key def-key]
  (when-let [t (find-key lines 0 (count lines) 0 top-key)]
    (let [t-end (block-end lines t)]
      (when-let [ci (child-indent lines t t-end)]
        (when-let [d (find-key lines (inc t) t-end ci def-key)]
          [d (block-end lines d)])))))

^:rct/test
(comment
  (definition-range ["x-compoje:" "  a: 1" "secrets:" "  pw:" "    file: x" "  # c" "  other:" "    file: y"]
    "secrets" "pw")
  ;=> [3 5]
  (definition-range ["secrets:" "  pw:" "    file: x" "configs: {}"] "secrets" "pw") ;=> [1 3]
  (definition-range ["secrets:" "  pw:" "    file: x"] "configs" "pw") ;=> nil
  )

(defn- dash-indent [line] (when-let [[_ s] (re-find #"^( *)- " line)] (count s)))

(defn xcompoje-item-range
  "[start end) of the list item under x-compoje: → secrets: whose name is
   `secret-name`."
  [lines secret-name]
  (when-let [x (find-key lines 0 (count lines) 0 "x-compoje")]
    (let [x-end (block-end lines x)]
      (when-let [ci (child-indent lines x x-end)]
        (when-let [s (find-key lines (inc x) x-end ci "secrets")]
          (let [s-end (block-end lines s)
                item-starts (filter #(some? (dash-indent (nth lines %))) (range (inc s) s-end))
                di (some-> (first item-starts) (->> (nth lines)) dash-indent)
                starts (filter #(= di (dash-indent (nth lines %))) item-starts)
                bounds (map vector starts (concat (rest starts) [s-end]))
                name-re (re-pattern (str "^ *(?:- +)?name *: *[\"']?"
                                         (java.util.regex.Pattern/quote (str secret-name))
                                         "[\"']? *(#.*)?$"))]
            (some (fn [[a b]]
                    (let [b (loop [b b] (if (and (> b (inc a)) (ignorable? (nth lines (dec b)))) (recur (dec b)) b))]
                      (when (some #(re-matches name-re (nth lines %)) (range a b))
                        [a b])))
                  bounds)))))))

^:rct/test
(comment
  (xcompoje-item-range ["x-compoje:" "  secrets:" "    - name: a" "      x: 1" "    # b" "    - name: b"
                        "      local-path: p" "services: {}"]
                       "b")
  ;=> [5 7]
  (xcompoje-item-range ["x-compoje:" "  secrets:" "  - name: \"a\"" "    x: 1"] "a") ;=> [2 4]
  )

(defn- remove-item-keys
  "Remove the lines of `ks` (single-line keys) in item range [a b) together
   with comment lines directly above them. nil if a key line can't be removed
   safely (it carries the list dash, or its value continues on the next line)."
  [lines [a b] ks]
  (let [key-re (re-pattern (str "^ *(" (str/join "|" (map #(java.util.regex.Pattern/quote %) ks)) ") *:.*"))
        targets (filter #(re-matches key-re (nth lines %)) (range a b))
        unsafe? (some (fn [i]
                        (let [l (nth lines i)]
                          (or (dash-indent l)
                              (re-matches #"^ *[^:]+: *(#.*)?$" l)          ; value on following lines
                              (re-find #": *[|>]" l))))
                      targets)]
    (when-not unsafe?
      (let [drop (into #{}
                       (mapcat (fn [i]
                                 (cons i (take-while #(and (>= % a) (re-matches #"\s*#.*" (nth lines %)))
                                                     (iterate dec (dec i)))))
                               targets))]
        (vec (keep-indexed (fn [i l] (when-not (drop i) l)) lines))))))

;; ------------------------------------------------------------------ planning

(defn- normalize-path [p] (str (fs/normalize (fs/path (str p)))))

(defn- result-is-string?
  "Is the whole provider result of secret `s` a string (needed for a from:
   path without spit-key)?"
  [s provider]
  (case (get provider "type")
    "compoje.providers.file/file" (= "raw" (file-provider/format-for (str (get s "file")) (get s "format")))
    "compoje.providers.ansible-vault/ansible-vault" (= "raw" (get s "format"))
    false))

(defn plan
  "Plan the conversions for parsed template `data` (string keys):
   {:convert [{:secret name :local-path L :from path :format F :defs [[top key] ...]}]
    :skip [{:secret name :local-path L :reason text}]}"
  [data]
  (let [providers (into {} (map (juxt #(str (get % "name")) identity)) (get-in data ["x-compoje" "providers"]))
        defs (for [top ["secrets" "configs"]
                   [k d] (get data top)
                   :when (and (map? d) (get d "file"))]
               {:top top :key k :file (normalize-path (get d "file"))})]
    (reduce
     (fn [acc s]
       (let [sname (str (get s "name"))
             lp (get s "local-path")
             spit-key (some-> (get s "spit-key") str)
             ;; without spit-key, edn/json is how the whole result was written
             fmt (when-not spit-key (#{"edn" "json"} (some-> (get s "format") str)))
             users (filter #(= (normalize-path lp) (:file %)) defs)
             skip #(update acc :skip conj {:secret sname :local-path lp :reason %})]
         (cond
           (nil? lp) acc
           (empty? users) (skip "local-path is not used as a Docker secret or config file in this stack; left as is.")
           (str/includes? sname ".") (skip "the secret name contains a dot, which a from: path can't express; rename it, then migrate again.")
           (and spit-key (str/includes? spit-key ".")) (skip (str "spit-key " spit-key " contains a dot, which a from: path can't express; rename the key in the secret source (e.g. "
                                                                  (str/replace spit-key "." "_") "), or keep local-path."))
           (and (nil? spit-key) (nil? fmt) (not (result-is-string? s (get providers (str (get s "provider"))))))
           (skip "without spit-key the whole provider result (a map) is written; add spit-key, or write x-compoje: {from: secrets.<name>.<key>} (one value) or {from: secrets.<name>, format: edn} (the whole map) by hand.")
           :else (update acc :convert conj (cond-> {:secret sname :local-path lp
                                                    :from (str "secrets." sname (when spit-key (str "." spit-key)))
                                                    :defs (mapv (juxt :top :key) users)}
                                             fmt (assoc :format fmt))))))
     {:convert [] :skip []}
     (get-in data ["x-compoje" "secrets"]))))

^:rct/test
(comment
  (plan {"x-compoje" {"providers" [{"name" "v" "type" "compoje.providers.ansible-vault/ansible-vault"}
                                   {"name" "f" "type" "compoje.providers.file/file"}]
                      "secrets" [{"name" "app" "provider" "v" "local-path" "./gen/pw.txt" "spit-key" "db_password"}
                                 {"name" "tls" "provider" "v" "local-path" "gen/tls" "spit-key" "cert.key"}
                                 {"name" "tok" "provider" "f" "file" "t.txt" "local-path" "gen/tok"}
                                 {"name" "map" "provider" "v" "local-path" "gen/map"}
                                 {"name" "conf" "provider" "v" "format" "edn" "local-path" "gen/conf.edn"}
                                 {"name" "unused" "provider" "f" "local-path" "gen/u"}
                                 {"name" "plain" "provider" "v"}]}
         "secrets" {"pw" {"file" "gen/pw.txt"} "tls" {"file" "gen/tls"} "tok" {"file" "gen/tok"}
                    "map" {"file" "gen/map"} "conf.edn" {"file" "gen/conf.edn"}}})
  ;=> {:convert [{:secret "app" :local-path "./gen/pw.txt" :from "secrets.app.db_password" :defs [["secrets" "pw"]]} {:secret "tok" :local-path "gen/tok" :from "secrets.tok" :defs [["secrets" "tok"]]} {:secret "conf" :local-path "gen/conf.edn" :from "secrets.conf" :defs [["secrets" "conf.edn"]] :format "edn"}] :skip [{:secret "tls" :local-path "gen/tls" :reason "spit-key cert.key contains a dot, which a from: path can't express; rename the key in the secret source (e.g. cert_key), or keep local-path."} {:secret "map" :local-path "gen/map" :reason "without spit-key the whole provider result (a map) is written; add spit-key, or write x-compoje: {from: secrets.<name>.<key>} (one value) or {from: secrets.<name>, format: edn} (the whole map) by hand."} {:secret "unused" :local-path "gen/u" :reason "local-path is not used as a Docker secret or config file in this stack; left as is."}]}
  )

;; ------------------------------------------------------------------ editing

(defn- new-definition
  "Replacement lines for definition [start end): the key line, x-compoje from
   (and format), plus a kept explicit name and the labels block, copied verbatim."
  [lines [start end] from fmt]
  (let [n (indent (nth lines start))
        step (- (or (child-indent lines start end) (+ n 2)) n)
        pad (apply str (repeat (+ n step) " "))
        body (range (inc start) end)
        child-key? (fn [i k] (key-line? (nth lines i) (+ n step) k))
        name-line (first (filter #(child-key? % "name") body))
        keep-name? (and name-line (not (str/includes? (nth lines name-line) "file-hash")))
        labels (when-let [l (first (filter #(child-key? % "labels") body))]
                 (subvec (vec lines) l (block-end lines l)))]
    (vec (concat [(nth lines start)
                  (str pad "x-compoje:")
                  (str pad (apply str (repeat step " ")) "from: " from)]
                 (when fmt [(str pad (apply str (repeat step " ")) "format: " fmt)])
                 (when keep-name? [(nth lines name-line)])
                 labels))))

(defn- expected-data
  "The data the template should have after converting `c`."
  [data {:keys [secret from defs] fmt :format}]
  (let [data (update-in data ["x-compoje" "secrets"]
                        (fn [ss] (mapv #(if (= secret (str (get % "name"))) (dissoc % "local-path" "spit-key") %) ss)))]
    (reduce (fn [d [top k]]
              (let [old (get-in d [top k])
                    keep-name? (and (get old "name") (not (str/includes? (str (get old "name")) "file-hash")))]
                (assoc-in d [top k]
                          (cond-> {"x-compoje" (cond-> {"from" from} fmt (assoc "format" fmt))}
                            keep-name? (assoc "name" (get old "name"))
                            (get old "labels") (assoc "labels" (get old "labels"))))))
            data
            defs)))

(defn- dropped-keys [data defs]
  (distinct (for [[top k] defs
                  key (keys (get-in data [top k]))
                  :when (not (#{"file" "name" "labels"} key))]
              (str top "." k "." key))))

(defn- edit-text
  "Apply conversion `c` to `text`. Returns the new text, or a skip reason."
  [text {:keys [secret local-path defs] :as c}]
  (let [lines (str/split text #"\n" -1)
        lines (reduce (fn [ls [top k]]
                        (when ls
                          (when-let [r (definition-range ls top k)]
                            (vec (concat (subvec ls 0 (first r)) (new-definition ls r (:from c) (:format c)) (subvec ls (second r)))))))
                      lines
                      ;; edit bottom-up so earlier ranges stay valid
                      (sort-by (fn [[top k]] (- (or (first (definition-range lines top k)) 0))) defs))
        lines (when lines
                (when-let [r (xcompoje-item-range lines secret)]
                  (remove-item-keys lines r ["local-path" "spit-key"])))]
    (if-not lines
      {:reason "could not locate the definitions in the template text; edit it by hand."}
      (let [new-text (str/join "\n" lines)
            lp (str local-path)
            still (first (keep-indexed (fn [i l] (when (or (str/includes? l lp)
                                                           (str/includes? l (normalize-path lp)))
                                                   (inc i)))
                                       lines))]
        (if still
          {:reason (str "the file " lp " is also used at line " still " of the template; edit it by hand.")}
          {:text new-text})))))

(defn convert
  "Convert the local-path secrets in template `text`. Returns
   {:text :converted [{:secret :from :defs :dropped-keys}] :skipped [{:secret :local-path :reason}]}.
   Each conversion is verified by parsing the result; failures are reverted
   and reported."
  [text]
  (let [data (try (yaml/load text)
                  (catch Exception e
                    (throw (ex-info (str "stack.tpl.yml is not valid YAML before rendering (" (ex-message e)
                                         "); can't migrate its secrets")
                                    {} e))))
        {:keys [convert skip]} (plan data)]
    (reduce (fn [{:keys [text] :as acc} c]
              (let [current (yaml/load text)
                    {new-text :text reason :reason} (edit-text text c)
                    reason (or reason
                               (when (not= (expected-data current c) (yaml/load new-text))
                                 "the edited template did not parse as expected; edit it by hand."))]
                (if reason
                  (update acc :skipped conj {:secret (:secret c) :local-path (:local-path c) :reason reason})
                  (-> acc
                      (assoc :text new-text)
                      (update :converted conj (assoc c :dropped-keys (vec (dropped-keys current (:defs c)))))))))
            {:text text :converted [] :skipped (vec skip)}
            convert)))

;; ------------------------------------------------------------------ references

(defn prefix-secret-refs
  "Rewrite references to provider results at the top level of the context
   (compoje 0.3.0: `<{ app.x }>`, `from: app.x`) to `secrets.<name>` for the
   declared secret `names`: inside <{ }> / <% %> tags, and x-compoje `from:`
   values. Returns [text count]."
  [text names]
  (if (empty? names)
    [text 0]
    (let [count* (atom 0)
          alt (str/join "|" (map #(java.util.regex.Pattern/quote %) (sort-by count > names)))
          ;; only where a variable can start (not inside 'path/file.ext' or "strings")
          ref-re (re-pattern (str "(?<=[\\s{(,!=%])(" alt ")(?=[.\\s|}%]|$)"))
          fix (fn [s] (str/replace s ref-re (fn [[_ n]] (swap! count* inc) (str "secrets." n))))
          text (str/replace text #"<\{.*?\}>|<%.*?%>" (fn [tag] (fix tag)))
          text (str/replace text (re-pattern (str "(?m)(\\bfrom:\\s*)(" alt ")(?=[.\\s,}#]|$)"))
                            (fn [[_ pre n]] (swap! count* inc) (str pre "secrets." n)))]
      [text @count*])))

^:rct/test
(comment
  (prefix-secret-refs (str "A: <{ app.db_user }>\nB: <{ secrets.app.x }>\nC: <{ values.app }>\n"
                           "D: <% if app.flag %>y<% endif %>\nE: <{ api-token|yaml-str }>\n"
                           "    x-compoje:\n      from: app.db_password\n    x-compoje: {from: api-token}\n")
                      ["app" "api-token"])
  ;=> ["A: <{ secrets.app.db_user }>\nB: <{ secrets.app.x }>\nC: <{ values.app }>\nD: <% if secrets.app.flag %>y<% endif %>\nE: <{ secrets.api-token|yaml-str }>\n    x-compoje:\n      from: secrets.app.db_password\n    x-compoje: {from: secrets.api-token}\n" 5]
  (prefix-secret-refs "A: <{ app.x }>" []) ;=> ["A: <{ app.x }>" 0]
  (prefix-secret-refs "V: <% file-path 'generated/cert.pem' %> <% file-hash \"cert.pem\" %> <{cert.x}>" ["cert"])
  ;=> ["V: <% file-path 'generated/cert.pem' %> <% file-hash \"cert.pem\" %> <{secrets.cert.x}>" 1]
  )
