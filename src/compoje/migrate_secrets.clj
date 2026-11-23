(ns compoje.migrate-secrets
  "Convert provider secrets written to disk (local-path) into Docker secrets
   created from memory (x-compoje: {from: ...}), editing the stack template
   text so comments, layout and Selmer tags survive.

   A spit-key with dots (grafana.ini) becomes `from: secrets.<name>` plus
   `key: grafana.ini`.

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
            [cheshire.core :as json]
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

(defn xcompoje-item-bounds
  "[[start end) ...] of the list items under x-compoje: → secrets:, in order
   (trailing blank/comment lines excluded)."
  [lines]
  (when-let [x (find-key lines 0 (count lines) 0 "x-compoje")]
    (let [x-end (block-end lines x)]
      (when-let [ci (child-indent lines x x-end)]
        (when-let [s (find-key lines (inc x) x-end ci "secrets")]
          (let [s-end (block-end lines s)
                item-starts (filter #(some? (dash-indent (nth lines %))) (range (inc s) s-end))
                di (some-> (first item-starts) (->> (nth lines)) dash-indent)
                starts (filter #(= di (dash-indent (nth lines %))) item-starts)]
            (mapv (fn [a b]
                    [a (loop [b b] (if (and (> b (inc a)) (ignorable? (nth lines (dec b)))) (recur (dec b)) b))])
                  starts (concat (rest starts) [s-end]))))))))

(defn xcompoje-item-range
  "[start end) of the list item under x-compoje: → secrets: whose name is
   `secret-name`."
  [lines secret-name]
  (let [name-re (re-pattern (str "^ *(?:- +)?name *: *[\"']?"
                                 (java.util.regex.Pattern/quote (str secret-name))
                                 "[\"']? *(#.*)?$"))]
    (some (fn [[a b]] (when (some #(re-matches name-re (nth lines %)) (range a b)) [a b]))
          (xcompoje-item-bounds lines))))

^:rct/test
(comment
  (xcompoje-item-range ["x-compoje:" "  secrets:" "    - name: a" "      x: 1" "    # b" "    - name: b"
                        "      local-path: p" "services: {}"]
                       "b")
  ;=> [5 7]
  (xcompoje-item-range ["x-compoje:" "  secrets:" "  - name: \"a\"" "    x: 1"] "a") ;=> [2 4]
  )

;; ------------------------------------------------------------------ secret names

(defn derive-secret-name
  "A name for a secret that has none (old compoje.edn didn't require one):
   the local-path (else file, else secret-path) file name without its
   extension, made safe ([A-Za-z0-9_-]) and unique among `taken`."
  [s taken]
  (let [src (some #(some-> (get s %) str not-empty) ["local-path" "file" "secret-path"])
        base (some-> src fs/file-name str (str/replace #"\.[^.]*$" "") (str/replace #"[^A-Za-z0-9_-]" "_"))
        base (if (str/blank? base) "secret" base)]
    (first (remove (set taken) (cons base (map #(str base "-" %) (iterate inc 2)))))))

^:rct/test
(comment
  (derive-secret-name {"local-path" "secrets/appsettings.json" "secret-path" "J/dev/appsettings.json"} #{})
  ;=> "appsettings"
  (derive-secret-name {"secret-path" "infra/traefik.json"} #{}) ;=> "traefik"
  (derive-secret-name {"file" "secrets/my app.vault.yml"} #{}) ;=> "my_app_vault"
  (derive-secret-name {"local-path" "a/pw.txt"} #{"pw" "pw-2"}) ;=> "pw-3"
  (derive-secret-name {} #{}) ;=> "secret"
  )

(defn- insert-name
  "Insert `name: n` as the first key of item [a b), or nil when the item's
   layout isn't block style."
  [lines [a _b] n]
  (let [line (nth lines a)]
    (when-let [[_ prefix rest-of-line] (re-matches #"^( *- +)(.*)$" line)]
      (when-not (re-find #"^[\[{]" rest-of-line)
        (let [pad (apply str (repeat (count prefix) " "))]
          (vec (concat (subvec lines 0 (inc a)) [(str pad "name: " n)] (subvec lines (inc a)))))))))

(defn add-missing-names
  "Give every x-compoje secret without a name one (see derive-secret-name),
   by inserting a `name:` line into the template text. Each edit is verified
   by parsing. Returns {:text :named [{:index i :name n}] :unnamed [i ...]}
   (indexes 0-based). Text that doesn't parse is returned as is."
  [text]
  (let [data (try (yaml/load text) (catch Exception _ nil))
        secrets (get-in data ["x-compoje" "secrets"])
        missing (keep-indexed (fn [i s] (when (and (map? s) (str/blank? (str (get s "name")))) i)) secrets)]
    (if (empty? missing)
      {:text text :named [] :unnamed []}
      (let [taken (atom (set (keep #(some-> (get % "name") str not-empty) secrets)))
            names (into {} (for [i missing]
                             (let [n (derive-secret-name (nth secrets i) @taken)]
                               (swap! taken conj n)
                               [i n])))
            lines (str/split text #"\n" -1)
            bounds (xcompoje-item-bounds lines)
            ;; bottom-up, so earlier item bounds stay valid
            edited (when (= (count bounds) (count secrets))
                     (reduce (fn [ls i] (when ls (insert-name ls (nth bounds i) (names i))))
                             lines (sort > missing)))
            new-text (some->> edited (str/join "\n"))
            expected (update-in data ["x-compoje" "secrets"]
                                (fn [ss] (vec (map-indexed (fn [i s] (if-let [n (names i)] (assoc s "name" n) s)) ss))))]
        (if (and new-text (= expected (yaml/load new-text)))
          {:text new-text :named (mapv (fn [i] {:index i :name (names i)}) missing) :unnamed []}
          {:text text :named [] :unnamed (vec missing)})))))

^:rct/test
(comment
  (add-missing-names (str "x-compoje:\n  secrets:\n  - provider: v\n    local-path: secrets/appsettings.json\n"
                          "  - name: other\n    provider: v\nservices: {}\n"))
  ;=> {:text "x-compoje:\n  secrets:\n  - provider: v\n    name: appsettings\n    local-path: secrets/appsettings.json\n  - name: other\n    provider: v\nservices: {}\n" :named [{:index 0 :name "appsettings"}] :unnamed []}
  (:unnamed (add-missing-names "x-compoje:\n  secrets: [{provider: v, local-path: a/pw.txt}]\n")) ;=> [0]
  (add-missing-names "x-compoje:\n  secrets:\n  - name: a\n") ;=> {:text "x-compoje:\n  secrets:\n  - name: a\n" :named [] :unnamed []}
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

(defn used-at
  "Where file `lp` (a local-path) is mentioned in the template `lines` or in
   the `others` texts ({file text}, e.g. x-compoje template files), apart
   from the x-compoje secret declarations (their local-path, secret-path,
   ... lines): \"line N\", the other file's name, or nil. Looks for the
   path as written, normalized, and its file name."
  [lines others lp]
  (let [needles (distinct (remove str/blank? [(str lp) (normalize-path lp) (str (fs/file-name (str lp)))]))
        declarations (set (mapcat (fn [[a b]] (range a b)) (xcompoje-item-bounds lines)))
        hit? (fn [l] (some #(str/includes? l %) needles))]
    (or (some (fn [[i l]] (when (and (not (declarations i)) (hit? l)) (str "line " (inc i))))
              (map-indexed vector lines))
        (some (fn [[f text]] (when (some hit? (str/split-lines text)) (str f))) others))))

^:rct/test
(comment
  (used-at ["x-compoje:" "  secrets:" "  - name: a" "    local-path: gen/pw.txt" "    secret-path: a/pw.txt"
            "services: {}"]
           {} "gen/pw.txt")
  ;=> nil
  (used-at ["x-compoje:" "  secrets:" "  - local-path: ./gen/pw.txt" "services:" "    - ./gen/pw.txt:/run/pw:ro"]
           {} "./gen/pw.txt")
  ;=> "line 5"
  (used-at ["a: <% file-path 'gen/pw.txt' %>"] {} "./gen/pw.txt") ;=> "line 1"
  (used-at ["a: 1"] {"conf/app.tpl" "password_file /run/secrets/pw.txt\n"} "gen/pw.txt") ;=> "conf/app.tpl"
  )

(defn plan
  "Plan the conversions for parsed template `data` (string keys):
   {:convert [{:secret name :local-path L :from path :format F :defs [[top key] ...]}]
    :remove [{:secret name :local-path L}]    ; local-path used nowhere: drop it
    :skip [{:secret name :local-path L :reason text}]}
   `used-at`: fn of a local-path → where else the stack uses the file, or nil."
  ([data] (plan data (constantly nil)))
  ([data used-at]
   (let [providers (into {} (map (juxt #(str (get % "name")) identity)) (get-in data ["x-compoje" "providers"]))
         defs (for [top ["secrets" "configs"]
                    [k d] (get data top)
                    :when (and (map? d) (get d "file"))]
                {:top top :key k :file (normalize-path (get d "file"))})]
     (reduce
      (fn [acc [i s]]
        (let [sname (if (str/blank? (str (get s "name"))) (str "#" (inc i)) (str (get s "name")))
              lp (get s "local-path")
              spit-key (some-> (get s "spit-key") str)
             ;; without spit-key, edn/json is how the whole result was written
              fmt (when-not spit-key (#{"edn" "json"} (some-> (get s "format") str)))
              users (filter #(= (normalize-path lp) (:file %)) defs)
              skip #(update acc :skip conj {:secret sname :local-path lp :reason %})]
          (cond
            (nil? lp) acc
            (str/blank? (str (get s "name"))) (skip "the secret has no name, which the x-compoje format requires; add name: ..., then migrate again.")
            (empty? users) (if-let [where (used-at lp)]
                             (skip (str "local-path is not used as a Docker secret or config file, but the file is used at "
                                        where "; left as is."))
                             (update acc :remove conj {:secret sname :local-path lp}))
            (str/includes? sname ".") (skip "the secret name contains a dot, which a from: path can't express; rename it, then migrate again.")
            (and (nil? spit-key) (nil? fmt) (not (result-is-string? s (get providers (str (get s "provider"))))))
            (skip "without spit-key the whole provider result (a map) is written; add spit-key, or write x-compoje: {from: secrets.<name>.<key>} (one value) or {from: secrets.<name>, format: edn} (the whole map) by hand.")
            :else (let [dotted (when (and spit-key (str/includes? spit-key ".")) spit-key)]
                    (update acc :convert conj
                            (cond-> {:secret sname :local-path lp
                                    ;; a dotted key can't be a path segment: from + key
                                     :from (str "secrets." sname (when (and spit-key (not dotted)) (str "." spit-key)))
                                     :defs (mapv (juxt :top :key) users)}
                              fmt (assoc :format fmt)
                              dotted (assoc :key dotted)))))))
      {:convert [] :remove [] :skip []}
      (map-indexed vector (get-in data ["x-compoje" "secrets"]))))))

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
  ;=> {:convert [{:secret "app" :local-path "./gen/pw.txt" :from "secrets.app.db_password" :defs [["secrets" "pw"]]} {:secret "tls" :local-path "gen/tls" :from "secrets.tls" :defs [["secrets" "tls"]] :key "cert.key"} {:secret "tok" :local-path "gen/tok" :from "secrets.tok" :defs [["secrets" "tok"]]} {:secret "conf" :local-path "gen/conf.edn" :from "secrets.conf" :defs [["secrets" "conf.edn"]] :format "edn"}] :remove [{:secret "unused" :local-path "gen/u"}] :skip [{:secret "map" :local-path "gen/map" :reason "without spit-key the whole provider result (a map) is written; add spit-key, or write x-compoje: {from: secrets.<name>.<key>} (one value) or {from: secrets.<name>, format: edn} (the whole map) by hand."}]}
  )

;; ------------------------------------------------------------------ editing

(defn- new-definition
  "Replacement lines for definition [start end): the key line, x-compoje from
   (and format), plus a kept explicit name and the labels block, copied verbatim."
  [lines [start end] from fmt k]
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
                 ;; quoted: a key like 1.0 or yes must stay a string
                 (when k [(str pad (apply str (repeat step " ")) "key: " (json/generate-string k))])
                 (when keep-name? [(nth lines name-line)])
                 labels))))

(defn- expected-data
  "The data the template should have after converting `c`."
  [data {:keys [secret from defs] fmt :format entry-key :key}]
  (let [data (update-in data ["x-compoje" "secrets"]
                        (fn [ss] (mapv #(if (= secret (str (get % "name"))) (dissoc % "local-path" "spit-key") %) ss)))]
    (reduce (fn [d [top k]]
              (let [old (get-in d [top k])
                    keep-name? (and (get old "name") (not (str/includes? (str (get old "name")) "file-hash")))]
                (assoc-in d [top k]
                          (cond-> {"x-compoje" (cond-> {"from" from} fmt (assoc "format" fmt) entry-key (assoc "key" entry-key))}
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
                            (vec (concat (subvec ls 0 (first r)) (new-definition ls r (:from c) (:format c) (:key c)) (subvec ls (second r)))))))
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
   {:text :converted [{:secret :from :defs :dropped-keys}] :removed [{:secret :local-path}]
    :skipped [{:secret :local-path :reason}]}.
   A local-path whose file nothing in the stack uses is removed. `others`:
   {file text} of other files the stack renders (x-compoje templates), searched
   for uses too. Each edit is verified by parsing the result; failures are
   reverted and reported."
  ([text] (convert text {}))
  ([text others]
   (let [data (try (yaml/load text)
                   (catch Exception e
                     (throw (ex-info (str "stack.tpl.yml is not valid YAML before rendering (" (ex-message e)
                                          "); can't migrate its secrets")
                                     {} e))))
         lines (str/split text #"\n" -1)
         {:keys [convert skip] removals :remove} (plan data #(used-at lines others %))
         converted
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
                 {:text text :converted [] :removed [] :skipped (vec skip)}
                 convert)]
     (reduce (fn [{:keys [text] :as acc} {:keys [secret local-path] :as r}]
               (let [ls (str/split text #"\n" -1)
                     new-lines (when-let [r (xcompoje-item-range ls secret)]
                                 (remove-item-keys ls r ["local-path" "spit-key"]))
                     new-text (some->> new-lines (str/join "\n"))
                     expected (update-in (yaml/load text) ["x-compoje" "secrets"]
                                         (fn [ss] (mapv #(if (= secret (str (get % "name"))) (dissoc % "local-path" "spit-key") %) ss)))]
                 (if (and new-text (= expected (yaml/load new-text)))
                   (-> acc (assoc :text new-text) (update :removed conj r))
                   (update acc :skipped conj {:secret secret :local-path local-path
                                              :reason "nothing in the stack uses the file, but local-path could not be removed from the text; remove it by hand."}))))
             converted
             removals))))

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
