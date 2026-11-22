(ns ansible-vault.yamlstar
  "Load YAML that contains inline ansible-vault values (`key: !vault |`).

   Optional part of the library: the only namespace that needs yamlstar.
   yamlstar.core/load rejects custom tags, so this runs yamlstar's own
   pipeline (parse -> compose -> resolve -> construct) and replaces every
   `!vault` scalar node with its decrypted string before construction.

   Uses yamlstar internals (parser/composer/resolver/constructor) as of
   org.yamlstar/yamlstar 0.1.23; re-check when upgrading yamlstar."
  (:require [ansible-vault.core :as vault]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [yamlstar.composer :as composer]
            [yamlstar.constructor :as constructor]
            [yamlstar.parser :as parser]
            [yamlstar.resolver :as resolver]))

(def str-tag "tag:yaml.org,2002:str")

(defn vault-node?
  "True for a resolved yamlstar scalar node tagged !vault."
  [node]
  (and (map? node)
       (= :scalar (:kind node))
       (= "!vault" (:tag node))))

^:rct/test
(comment
  (vault-node? {:kind :scalar :tag "!vault" :value "x"}) ;=> true
  (vault-node? {:kind :scalar :tag "tag:yaml.org,2002:str" :value "x"}) ;=> false
  (vault-node? {:kind :mapping :tag "!vault"}) ;=> false
  (vault-node? "!vault") ;=> false
  )

(defn decrypt-vault-nodes
  "Replace !vault scalar nodes in a resolved yamlstar node tree with plain
   string nodes holding the decrypted value (via `decrypt-fn`, default
   ansible-vault.core/decrypt with `secrets`)."
  ([node secrets]
   (decrypt-vault-nodes node secrets vault/decrypt))
  ([node secrets decrypt-fn]
   (walk/postwalk (fn [n]
                    (if (vault-node? n)
                      (assoc n :tag str-tag :value (decrypt-fn (:value n) secrets))
                      n))
                  node)))

^:rct/test
(comment
  (decrypt-vault-nodes {:kind :mapping
                        :value [[{:kind :scalar :tag str-tag :value "a"}
                                 {:kind :scalar :tag "!vault" :value "ENC"}]]}
                       "pw"
                       (fn [v s] (str "dec(" v "," s ")")))
  ;=> {:kind :mapping
  ;    :value [[{:kind :scalar :tag "tag:yaml.org,2002:str" :value "a"}
  ;             {:kind :scalar :tag "tag:yaml.org,2002:str" :value "dec(ENC,pw)"}]]}
  )

(defn load-yaml
  "Parse a YAML string (first document), decrypting `!vault` values with
   `secrets` (see ansible-vault.core for the accepted forms).
   Keys are returned as yamlstar returns them (strings)."
  [yaml-str secrets]
  (when yaml-str
    (-> (parser/parse yaml-str nil)
        composer/compose
        resolver/resolve
        (decrypt-vault-nodes secrets)
        constructor/construct)))

^:rct/test
(comment
  (load-yaml (str "plain: 1\nsecret: !vault |\n"
                  (->> (vault/encrypt "s3cret" "pw")
                       str/split-lines
                       (map #(str "  " %))
                       (str/join "\n"))
                  "\n")
             "pw")
  ;=> {"plain" 1, "secret" "s3cret"}
  (load-yaml "a: [1, 2]\n" "unused") ;=> {"a" [1 2]}
  (load-yaml nil "pw") ;=> nil
  )
