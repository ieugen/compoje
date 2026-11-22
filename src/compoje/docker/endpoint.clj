(ns compoje.docker.endpoint
  "Which Docker daemon to talk to, resolved like the docker CLI does, without
   needing the CLI: docker contexts are read from ~/.docker/contexts.

   Order: explicit context name (--context / x-compoje docker.context),
   DOCKER_HOST, DOCKER_CONTEXT, currentContext in ~/.docker/config.json,
   unix:///var/run/docker.sock. The context named \"default\" means
   DOCKER_HOST or the local socket."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.security MessageDigest)))

(def default-host "unix:///var/run/docker.sock")

(defn sha256-hex [^String s]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn context-dir-name
  "Docker stores a context's files under the sha256 of its name."
  [context-name]
  (sha256-hex context-name))

^:rct/test
(comment
  (context-dir-name "dre-infra") ;=> "1eb6f8b37c6c8cfea8dadd5ec3abe9bd4480379ea7d6cbc41e9e3029ef15a1f7"
  )

(defn parse-host
  "Parse a docker host URL into an endpoint map:
   unix:///path, tcp://host[:port], ssh://[user@]host[:port].
   tcp without a port defaults to 2376 with TLS, 2375 without."
  ([host] (parse-host host false))
  ([host tls?]
   (let [uri (URI. (str/trim (str host)))
         scheme (some-> (.getScheme uri) str/lower-case)
         port (when (pos? (.getPort uri)) (.getPort uri))]
     (case scheme
       "unix" {:scheme :unix :path (.getPath uri) :host-url host}
       ("tcp" "http" "https") {:scheme :tcp :host (.getHost uri)
                               :port (or port (if tls? 2376 2375))
                               :host-url host}
       "ssh" (cond-> {:scheme :ssh :host (.getHost uri) :host-url host}
               (.getUserInfo uri) (assoc :user (.getUserInfo uri))
               port (assoc :port port))
       (throw (ex-info (str "Unsupported docker host: " host
                            ". Supported: unix://, tcp://, ssh://")
                       {:host host}))))))

^:rct/test
(comment
  (parse-host "unix:///var/run/docker.sock")
  ;=> {:scheme :unix :path "/var/run/docker.sock" :host-url "unix:///var/run/docker.sock"}
  (parse-host "ssh://ubuntu@infra5.example.com:2122")
  ;=> {:scheme :ssh :host "infra5.example.com" :user "ubuntu" :port 2122 :host-url "ssh://ubuntu@infra5.example.com:2122"}
  (parse-host "ssh://k1.example.com")
  ;=> {:scheme :ssh :host "k1.example.com" :host-url "ssh://k1.example.com"}
  (parse-host "tcp://10.0.0.5" true) ;=> {:scheme :tcp :host "10.0.0.5" :port 2376 :host-url "tcp://10.0.0.5"}
  (parse-host "tcp://10.0.0.5:2375") ;=> {:scheme :tcp :host "10.0.0.5" :port 2375 :host-url "tcp://10.0.0.5:2375"}
  (try (parse-host "npipe:////./pipe/docker_engine") (catch Exception e (ex-message e)))
  ;=> "Unsupported docker host: npipe:////./pipe/docker_engine. Supported: unix://, tcp://, ssh://"
  )

(defn choose-source
  "Pure part of the resolution order. Returns {:context name} or {:host url}
   with :source saying where it came from."
  [{:keys [context env current-context]}]
  (let [docker-host (not-empty (get env "DOCKER_HOST"))
        env-context (not-empty (get env "DOCKER_CONTEXT"))
        named (fn [n source]
                (if (= "default" n)
                  {:host (or docker-host default-host) :source source :context "default"}
                  {:context n :source source}))]
    (cond
      (not (str/blank? context)) (named context :flag)
      docker-host {:host docker-host :source :DOCKER_HOST}
      env-context (named env-context :DOCKER_CONTEXT)
      (not (str/blank? current-context)) (named current-context :config)
      :else {:host default-host :source :default})))

^:rct/test
(comment
  (choose-source {:context "prod" :env {"DOCKER_HOST" "tcp://x"}}) ;=> {:context "prod" :source :flag}
  (choose-source {:env {"DOCKER_HOST" "tcp://x" "DOCKER_CONTEXT" "c"}}) ;=> {:host "tcp://x" :source :DOCKER_HOST}
  (choose-source {:env {"DOCKER_CONTEXT" "c"} :current-context "d"}) ;=> {:context "c" :source :DOCKER_CONTEXT}
  (choose-source {:env {} :current-context "d"}) ;=> {:context "d" :source :config}
  (choose-source {:env {}}) ;=> {:host "unix:///var/run/docker.sock" :source :default}
  (choose-source {:context "default" :env {}}) ;=> {:host "unix:///var/run/docker.sock" :source :flag :context "default"}
  )

(defn docker-config-dir
  "$DOCKER_CONFIG or ~/.docker."
  [env home]
  (or (not-empty (get env "DOCKER_CONFIG")) (str (fs/path home ".docker"))))

(defn- read-json [f]
  (when (fs/exists? f) (json/parse-string (slurp (str f)))))

(defn- tls-files
  "{:ca :cert :key} paths that exist in `dir`, or nil."
  [dir]
  (let [files (into {}
                    (for [[k f] {:ca "ca.pem" :cert "cert.pem" :key "key.pem"}
                          :let [p (fs/path dir f)]
                          :when (fs/exists? p)]
                      [k (str p)]))]
    (not-empty files)))

(defn context-endpoint
  "Endpoint of a named context from <config-dir>/contexts."
  [config-dir context-name]
  (let [dir (context-dir-name context-name)
        meta-file (fs/path config-dir "contexts" "meta" dir "meta.json")
        meta (read-json meta-file)
        {:strs [Host SkipTLSVerify]} (get-in meta ["Endpoints" "docker"])]
    (when-not meta
      (throw (ex-info (str "Docker context not found: " context-name " (no " meta-file ")")
                      {:context context-name})))
    (when (str/blank? Host)
      (throw (ex-info (str "Docker context " context-name " has no docker endpoint") {:context context-name})))
    (let [tls (tls-files (fs/path config-dir "contexts" "tls" dir "docker"))
          endpoint (parse-host Host (some? tls))]
      (cond-> (assoc endpoint :context context-name)
        (and tls (= :tcp (:scheme endpoint))) (assoc :tls (assoc tls :verify (not SkipTLSVerify)))))))

(defn- env-tls
  "TLS settings for a DOCKER_HOST tcp endpoint from DOCKER_TLS_VERIFY / DOCKER_CERT_PATH."
  [env config-dir]
  (let [verify? (not (str/blank? (get env "DOCKER_TLS_VERIFY")))
        cert-path (or (not-empty (get env "DOCKER_CERT_PATH")) config-dir)
        files (tls-files cert-path)]
    (when (or verify? (not-empty (get env "DOCKER_CERT_PATH")))
      (assoc files :verify verify?))))

(defn resolve-endpoint
  "Resolve the endpoint to use. `opts`: :context (explicit name), :env (map,
   default System/getenv), :home (default user.home)."
  ([] (resolve-endpoint {}))
  ([{:keys [context env home]}]
   (let [env (or env (into {} (System/getenv)))
         home (or home (System/getProperty "user.home"))
         config-dir (docker-config-dir env home)
         current (get (read-json (fs/path config-dir "config.json")) "currentContext")
         choice (choose-source {:context context :env env :current-context current})]
     (if (:host choice)
       (let [tls (when (str/starts-with? (str/lower-case (:host choice)) "tcp://")
                   (env-tls env config-dir))]
         (cond-> (assoc (parse-host (:host choice) (some? tls)) :source (:source choice))
           (:context choice) (assoc :context (:context choice))
           tls (assoc :tls tls)))
       (assoc (context-endpoint config-dir (:context choice)) :source (:source choice))))))

(comment
  (resolve-endpoint)
  (resolve-endpoint {:context "dre-infra"}))
