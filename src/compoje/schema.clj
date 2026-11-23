(ns compoje.schema
  "Validation of the x-compoje configuration (after --set), with malli.

   - docker: closed map with types and allowed values;
   - providers / secrets: the keys of the built-in providers are checked;
     custom provider types are left open;
   - cross checks: secrets reference an existing provider, unique names;
   - other top-level keys are allowed (they reach the template) but get a
     warning, since they are often typos.

   `validate` also coerces --set strings (\"true\" -> true) where the schema
   wants booleans."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]))

(def Docker
  [:map {:closed true}
   [:stack {:optional true} :string]
   [:context {:optional true} :string]
   [:prune {:optional true} :boolean]
   [:resolve-image {:optional true} [:enum "always" "changed" "never"]]
   [:with-registry-auth {:optional true} :boolean]
   [:driver {:optional true} [:enum "docker" "docker-cli" "docker-api"]]
   [:ssh-mode {:optional true} [:enum "dial-stdio" "forward"]]
   [:allow-insecure-tcp {:optional true} :boolean]
   ;; ignored with a warning since the stack is no longer written to disk
   [:compose-file {:optional true} :any]])

(def Render
  [:map {:closed true}
   ;; undefined secrets.* variables: error (default) or warn and render empty
   [:undefined {:optional true} [:enum "error" "warn"]]])

(def provider-keys
  "Settings of the built-in provider types."
  {"compoje.providers.ansible-vault/ansible-vault"
   [[:password-file {:optional true} :string]
    [:password-env {:optional true} :string]
    [:vault-id {:optional true} :string]]
   "compoje.providers.vault/vault"
   [[:addr {:optional true} :string]
    [:token {:optional true} :string]
    [:app-role-id {:optional true} :string]
    [:app-secret-id {:optional true} :string]]
   "compoje.providers.file/file"
   [[:dir {:optional true} :string]]})

(def secret-keys
  "Secret settings per built-in provider type (besides the common ones)."
  {"compoje.providers.ansible-vault/ansible-vault"
   [[:file :string]
    [:format {:optional true} [:enum "yaml" "raw"]]]
   "compoje.providers.vault/vault"
   [[:mount-path :string]
    [:secret-path :string]
    [:opts {:optional true} :map]
    [:format {:optional true} [:enum "edn" "json"]]]
   "compoje.providers.file/file"
   [[:file :string]
    [:format {:optional true} [:enum "yaml" "json" "edn" "raw"]]]})

(defn provider-schema [type]
  (into [:map {:closed (contains? provider-keys type)}
         [:name :string]
         [:type [:re {:error/message "should be a provider type like compoje.providers.file/file"} #"^[^/\s]+/[^/\s]+$"]]]
        (get provider-keys type)))

(defn secret-schema [type]
  (into [:map {:closed (contains? secret-keys type)}
         [:name :string]
         [:provider :string]
         [:local-path {:optional true} :string]
         [:spit-key {:optional true} :string]]
        (get secret-keys type)))

(def Config
  [:map
   [:docker {:optional true} [:maybe :map]]
   [:providers {:optional true} [:sequential [:map [:name :any] [:type :any]]]]
   [:secrets {:optional true} [:sequential [:map [:name :any] [:provider :any]]]]
   [:values {:optional true} [:maybe :map]]
   [:render {:optional true} [:maybe :map]]])

(def known-keys #{:docker :providers :secrets :values :render})

;; ------------------------------------------------------------------ messages

(defn- path-str [path]
  (reduce (fn [s p] (if (number? p) (str s "[" p "]") (str s (when (seq s) ".") (name p)))) "" path))

(defn- flatten-errors
  "Humanized malli errors (nested maps/vectors of messages) as [[path msg] ...]."
  ([x] (flatten-errors [] x))
  ([path x]
   (cond
     (map? x) (mapcat (fn [[k v]] (flatten-errors (conj path k) v)) x)
     (and (sequential? x) (every? string? x)) (map (fn [msg] [path msg]) x)
     (sequential? x) (mapcat (fn [i v] (when v (flatten-errors (conj path i) v))) (range) x)
     :else [[path (str x)]])))

(defn- edit-distance [a b]
  (let [a (vec a) b (vec b)]
    (peek (reduce (fn [prev i]
                    (reduce (fn [row j]
                              (conj row (min (inc (peek row)) (inc (prev (inc j)))
                                             (+ (prev j) (if (= (a i) (b j)) 0 1)))))
                            [(inc i)] (range (count b))))
                  (vec (range (inc (count b)))) (range (count a))))))

(defn- similar [k candidates]
  (first (sort-by #(edit-distance (name k) (name %))
                  (filter #(<= (edit-distance (name k) (name %)) 2) candidates))))

(defn- map-keys
  "Keys a malli :map schema allows."
  [schema]
  (set (map first (m/children (m/schema schema)))))

(defn- explain
  "Error messages for `value` against `schema`, prefixed with `base-path`.
   Unknown keys of the (closed) map get a \"did you mean\" from its own keys."
  [schema value base-path]
  (when-let [e (m/explain schema value)]
    (let [allowed (map-keys schema)]
      (for [[p msg] (flatten-errors (me/humanize e))]
        (str (path-str (into base-path p)) ": "
             (if (= "disallowed key" msg)
               (str "unknown key" (when-let [s (similar (last p) allowed)] (str "; did you mean " (name s) "?")))
               msg))))))

;; ------------------------------------------------------------------ validate

(defn validate
  "Check `config` (the merged x-compoje map, keyword keys). Returns
   {:config coerced-config :errors [msg ...] :warnings [msg ...]}."
  [config]
  (let [config (-> (m/decode Config (or config {}) mt/string-transformer)
                   (update :docker #(when % (m/decode Docker % mt/string-transformer))))
        config (cond-> config (nil? (:docker config)) (dissoc :docker))
        {:keys [providers secrets]} config
        provider-type (into {} (map (juxt #(str (:name %)) #(str (:type %)))) providers)
        dupes (fn [what xs]
                (for [[n c] (frequencies (map #(str (:name %)) xs)) :when (> c 1)]
                  (str what " name " n " is used " c " times")))
        errors (concat
                (explain Config config [])
                (when (map? (:docker config)) (explain Docker (:docker config) [:docker]))
                (when (map? (:render config)) (explain Render (:render config) [:render]))
                (mapcat (fn [i p] (explain (provider-schema (str (:type p))) p [:providers i])) (range) providers)
                (mapcat (fn [i s]
                          (let [pname (str (:provider s))
                                type (provider-type pname)]
                            (concat
                             (when (and (some? (:provider s)) (not type))
                               [(str "secrets[" i "].provider: unknown provider " pname
                                     (if (seq provider-type)
                                       (str " (providers: " (str/join ", " (sort (keys provider-type))) ")")
                                       " (no providers are declared)"))])
                             (explain (secret-schema type) s [:secrets i]))))
                        (range) secrets)
                (dupes "provider" providers)
                (dupes "secret" secrets))
        warnings (for [k (keys config) :when (not (known-keys k))]
                   (str "unknown x-compoje key " (name k)
                        (if-let [s (similar k known-keys)]
                          (str "; did you mean " (name s) "?")
                          " (kept, and available in the template)")))]
    {:config config :errors (vec (sort (distinct errors))) :warnings (vec (sort warnings))}))

^:rct/test
(comment
  (validate {:docker {:stack "web" :prune "false"}})
  ;=> {:config {:docker {:stack "web" :prune false}} :errors [] :warnings []}
  (:errors (validate {:docker {:stak "web" :resolve-image "sometimes"}}))
  ;=> ["docker.resolve-image: should be either \"always\", \"changed\" or \"never\"" "docker.stak: unknown key; did you mean stack?"]
  (:warnings (validate {:valus {:a 1} :extra 1}))
  ;=> ["unknown x-compoje key extra (kept, and available in the template)" "unknown x-compoje key valus; did you mean values?"]
  (:errors (validate {:providers [{:name "v" :type "compoje.providers.file/file" :dri "x"}]
                      :secrets [{:name "values" :provider "vualt" :file "f"}]}))
  ;=> ["providers[0].dri: unknown key; did you mean dir?" "secrets[0].provider: unknown provider vualt (providers: v)"]
  (:errors (validate {:providers [{:name "f" :type "compoje.providers.file/file"}]
                      :secrets [{:name "a" :provider "f"} {:name "a" :provider "f" :file "x"}]}))
  ;=> ["secret name a is used 2 times" "secrets[0].file: missing required key"]
  (:errors (validate {:render {:undefined "warn"}})) ;=> []
  (:errors (validate {:render {:undefined "ignore" :x 1}}))
  ;=> ["render.undefined: should be either \"error\" or \"warn\"" "render.x: unknown key"]
  (:errors (validate {:providers [{:name "m" :type "my.ns/custom" :anything 1}]
                      :secrets [{:name "s" :provider "m" :whatever 2}]}))
  ;=> []
  )
