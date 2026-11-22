(ns compoje.deploy.registry-auth
  "Registry credentials for the X-Registry-Auth header, from the docker client
   config (~/.docker/config.json) without the docker CLI: inline `auths`
   entries, or a credential helper (credHelpers / credsStore), which runs the
   separate `docker-credential-<name>` program, like docker does."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.util Base64)))

(def docker-hub "https://index.docker.io/v1/")

(defn image-registry
  "Registry host of an image reference; Docker Hub images give \"docker.io\"."
  [image]
  (let [[first-part & more] (str/split (str image) #"/")]
    (if (and (seq more)
             (or (str/includes? first-part ".") (str/includes? first-part ":") (= "localhost" first-part)))
      first-part
      "docker.io")))

^:rct/test
(comment
  (image-registry "nginx:stable") ;=> "docker.io"
  (image-registry "docker.io/nginx:stable") ;=> "docker.io"
  (image-registry "library/nginx") ;=> "docker.io"
  (image-registry "registry.example.com:5000/team/app:1") ;=> "registry.example.com:5000"
  (image-registry "localhost/app") ;=> "localhost"
  (image-registry "ghcr.io/org/app@sha256:abc") ;=> "ghcr.io"
  )

(defn config-keys
  "Keys a registry may have in config.json `auths` (docker writes the Hub as
   https://index.docker.io/v1/)."
  [registry]
  (if (#{"docker.io" "index.docker.io" "registry-1.docker.io"} registry)
    [docker-hub "index.docker.io" "docker.io" "registry-1.docker.io"]
    [registry (str "https://" registry) (str "http://" registry)]))

(defn server-address [registry]
  (first (config-keys registry)))

(defn- b64-decode [s] (String. (.decode (Base64/getDecoder) (str s)) "UTF-8"))

(defn inline-auth
  "Credentials from an `auths` entry ({\"auth\" base64(user:pass)} or an
   identitytoken), or nil."
  [config registry]
  (some (fn [k]
          (when-let [{:strs [auth identitytoken username password]} (get-in config ["auths" k])]
            (cond
              identitytoken {"identitytoken" identitytoken "serveraddress" (server-address registry)}
              auth (let [[u p] (str/split (b64-decode auth) #":" 2)]
                     {"username" u "password" p "serveraddress" (server-address registry)})
              username {"username" username "password" password "serveraddress" (server-address registry)})))
        (config-keys registry)))

^:rct/test
(comment
  (inline-auth {"auths" {"https://index.docker.io/v1/" {"auth" "dTpwOmFzcw=="}}} "docker.io")
  ;=> {"username" "u" "password" "p:ass" "serveraddress" "https://index.docker.io/v1/"}
  (inline-auth {"auths" {"ghcr.io" {"identitytoken" "tok"}}} "ghcr.io")
  ;=> {"identitytoken" "tok" "serveraddress" "ghcr.io"}
  (inline-auth {"auths" {"ghcr.io" {}}} "ghcr.io") ;=> nil
  )

(defn helper-name
  "Credential helper for `registry`: credHelpers entry, else credsStore."
  [config registry]
  (or (some #(get-in config ["credHelpers" %]) (config-keys registry))
      (get config "credsStore")))

(defn helper-credentials
  "Run `docker-credential-<helper> get` for `registry`. A missing entry
   gives nil (anonymous pull), like docker."
  [helper registry]
  (let [server (server-address registry)
        {:keys [exit out err]} (try (p/shell {:in server :out :string :err :string :continue true}
                                             (str "docker-credential-" helper) "get")
                                    (catch Exception e {:exit -1 :err (ex-message e)}))]
    (cond
      (zero? exit) (let [{:strs [Username Secret]} (json/parse-string out)]
                     (if (= "<token>" Username)
                       {"identitytoken" Secret "serveraddress" server}
                       {"username" Username "password" Secret "serveraddress" server}))
      (str/includes? (str out err) "credentials not found") nil
      :else (throw (ex-info (str "Credential helper docker-credential-" helper " failed for " server ": "
                                 (str/trim (str err out)))
                            {:helper helper :registry registry})))))

(defn credentials
  "Credentials for `image` from the docker `config` map, or nil."
  [config image]
  (let [registry (image-registry image)]
    (or (when-let [h (helper-name config registry)] (helper-credentials h registry))
        (inline-auth config registry))))

(defn header
  "X-Registry-Auth value: base64url of the credentials JSON."
  [creds]
  (.encodeToString (Base64/getUrlEncoder) (.getBytes ^String (json/generate-string creds) "UTF-8")))

^:rct/test
(comment
  (String. (.decode (java.util.Base64/getUrlDecoder) ^String (header {"username" "u"})) "UTF-8")
  ;=> "{\"username\":\"u\"}"
  )
