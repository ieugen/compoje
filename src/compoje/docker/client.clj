(ns compoje.docker.client
  "Docker Engine API client without the docker CLI.

   Transports (see compoje.docker.endpoint for how the endpoint is chosen):
   - unix: the daemon socket (java.nio UnixDomainSocketAddress)
   - tcp:  plain TCP, refused unless :allow-insecure-tcp (it is root on the host)
   - tls:  tcp endpoint with :tls (ca/cert/key PEM files), mutual TLS
   - ssh:  `ssh [-l user] [-p port] host -- docker system dial-stdio`, like the
           docker CLI: HTTP over the ssh process's stdin/stdout. Needs local
           OpenSSH and the docker binary on the remote host. :ssh-mode :forward
           instead forwards the remote socket to a local one in a 0700 temp dir
           (no remote docker binary needed).

   One connection is opened per `with-connection`, reused for all requests
   (keep-alive), and closed (ssh process stopped) at the end."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [compoje.docker.http :as http]
            [compoje.docker.tls :as tls])
  (:import (java.io BufferedInputStream BufferedOutputStream)
           (java.net InetSocketAddress Socket StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels SocketChannel)
           (javax.net.ssl SSLSocket)))

(def max-api-version
  "Newest Docker Engine API version compoje speaks. Older daemons get their own."
  "1.47")

(def connect-timeout-ms 30000)

;; ------------------------------------------------------------------ transports

(defn- streams [in out close]
  {:in (BufferedInputStream. in) :out (BufferedOutputStream. out) :close close})

(defn- connect-unix [path]
  (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.connect ch (UnixDomainSocketAddress/of ^String path))
      (catch Exception e
        (.close ch)
        (throw (ex-info (str "Cannot connect to the Docker socket " path ": " (ex-message e))
                        {:path path} e))))
    (streams (Channels/newInputStream ch) (Channels/newOutputStream ch) #(.close ch))))

(defn- connect-tcp [{:keys [host port tls allow-insecure-tcp]}]
  (when-not (or tls allow-insecure-tcp)
    (throw (ex-info (str "Refusing plain TCP to tcp://" host ":" port
                         ": it gives root on that host to anyone on the network."
                         " Use TLS (ca/cert/key), ssh://, or allow it explicitly.")
                    {:host host :port port})))
  (let [socket (if tls
                 (let [read-pem #(some-> (get tls %) slurp)
                       ctx (tls/ssl-context {:ca (read-pem :ca) :cert (read-pem :cert) :key (read-pem :key)
                                             :verify (get tls :verify true)})
                       ^SSLSocket s (.createSocket (.getSocketFactory ctx))]
                   (when (get tls :verify true)
                     (let [params (.getSSLParameters s)]
                       (.setEndpointIdentificationAlgorithm params "HTTPS")
                       (.setSSLParameters s params)))
                   s)
                 (Socket.))]
    (try
      (.connect ^Socket socket (InetSocketAddress. ^String host (int port)) (int connect-timeout-ms))
      (when (instance? SSLSocket socket) (.startHandshake ^SSLSocket socket))
      (catch Exception e
        (.close ^Socket socket)
        (throw (ex-info (str "Cannot connect to Docker at " host ":" port ": " (ex-message e))
                        {:host host :port port} e))))
    (streams (.getInputStream ^Socket socket) (.getOutputStream ^Socket socket) #(.close ^Socket socket))))

(defn ssh-args
  "ssh command line for an endpoint: dial-stdio runs the remote docker's
   stdio proxy. -T: no tty; the user's ~/.ssh/config, agent and known_hosts
   apply as usual."
  [{:keys [host user port ssh-command]} remote-command]
  (-> (vec (or ssh-command ["ssh"]))
      (into ["-o" (str "ConnectTimeout=" (quot connect-timeout-ms 1000)) "-T"])
      (cond-> user (into ["-l" user])
              port (into ["-p" (str port)]))
      (into ["--" host])
      (into remote-command)))

^:rct/test
(comment
  (ssh-args {:host "h" :user "ubuntu" :port 2122} ["docker" "system" "dial-stdio"])
  ;=> ["ssh" "-o" "ConnectTimeout=30" "-T" "-l" "ubuntu" "-p" "2122" "--" "h" "docker" "system" "dial-stdio"]
  (ssh-args {:host "h"} ["true"]) ;=> ["ssh" "-o" "ConnectTimeout=30" "-T" "--" "h" "true"]
  )

(defn- connect-process
  "HTTP over a child process's stdin/stdout (ssh ... docker system dial-stdio)."
  [cmd]
  (log/debug "Docker connection via" (str/join " " cmd))
  (let [proc (p/process cmd {:err :string})]
    (streams (:out proc) (:in proc)
             #(do (try (.close ^java.io.OutputStream (:in proc)) (catch Exception _))
                  (p/destroy-tree proc)))))

(defn- connect-ssh-forward
  "Forward the remote docker socket to a socket in a private temp dir."
  [endpoint]
  (let [dir (fs/create-temp-dir {:prefix "compoje-ssh-"
                                 :posix-file-permissions "rwx------"})
        local (str (fs/path dir "docker.sock"))
        remote (or (:remote-socket endpoint) "/var/run/docker.sock")
        cmd (-> (ssh-args endpoint [])
                (as-> c (let [[before after] (split-with #(not= "--" %) c)]
                          (vec (concat before ["-N" "-o" "ExitOnForwardFailure=yes"
                                               "-L" (str local ":" remote)]
                                       after)))))
        proc (p/process cmd {:err :string :in :inherit})
        cleanup #(do (p/destroy-tree proc) (fs/delete-tree dir))]
    (log/debug "Docker connection via" (str/join " " cmd))
    (loop [waited 0]
      (cond
        (fs/exists? local) nil
        (not (p/alive? proc)) (do (cleanup)
                                  (throw (ex-info (str "ssh forward failed: " (str/trim (str (:err @proc))))
                                                  {:cmd cmd})))
        (> waited connect-timeout-ms) (do (cleanup)
                                          (throw (ex-info "ssh forward: timed out waiting for the socket" {:cmd cmd})))
        :else (do (Thread/sleep 100) (recur (+ waited 100)))))
    (let [conn (connect-unix local)]
      (assoc conn :close #(do ((:close conn)) (cleanup))))))

(defn connect
  "Open a connection to `endpoint` (from compoje.docker.endpoint). Returns
   {:in :out :close :endpoint}."
  [{:keys [scheme path ssh-mode] :as endpoint}]
  (log/debug "Connecting to Docker at" (:host-url endpoint))
  (-> (case scheme
        :unix (connect-unix path)
        :tcp (connect-tcp endpoint)
        :ssh (if (= :forward ssh-mode)
               (connect-ssh-forward endpoint)
               (connect-process (ssh-args endpoint ["docker" "system" "dial-stdio"])))
        :process (connect-process (:command endpoint))
        (throw (ex-info (str "Unsupported endpoint scheme: " scheme) {:endpoint endpoint})))
      (assoc :endpoint endpoint)))

;; ------------------------------------------------------------------ requests

(defn- version-parts [v] (mapv #(Long/parseLong %) (str/split (str v) #"\.")))

(defn negotiate-version
  "API version to use: the daemon's, capped at `max-api-version`."
  [server-version]
  (if (and server-version
           (neg? (compare (version-parts server-version) (version-parts max-api-version))))
    server-version
    max-api-version))

^:rct/test
(comment
  (negotiate-version "1.57") ;=> "1.47"
  (negotiate-version "1.44") ;=> "1.44"
  (negotiate-version "1.9") ;=> "1.9"
  (negotiate-version nil) ;=> "1.47"
  )

(defn raw-request
  "Send one request on `conn`; returns the response with :body bytes.
   A connection that broke (e.g. the ssh process died) reports its stderr."
  [{:keys [in out] :as conn} request]
  (try
    (http/round-trip in out request)
    (catch Exception e
      (throw (ex-info (str "Docker API request " (str/upper-case (name (:method request))) " "
                           (:path request) " failed: " (ex-message e)
                           (when-let [ep (:endpoint conn)] (str " (" (:host-url ep) ")")))
                      {:request (dissoc request :body :headers)}
                      e)))))

(defn request
  "Versioned API request. `path` is without the /vX.Y prefix. `body` is
   encoded as JSON. Returns {:status :headers :body} with :body parsed from
   JSON when it is JSON. Non-2xx statuses throw unless (:ok-statuses opts)
   lists them."
  ([conn method path] (request conn method path nil))
  ([{:keys [api-version] :as conn} method path {:keys [body query headers ok-statuses]}]
   (let [qs (when (seq query)
              (str "?" (str/join "&" (for [[k v] query]
                                       (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))))
         resp (raw-request conn {:method method
                                 :path (str "/v" api-version path qs)
                                 :headers (cond-> (merge {} headers)
                                            (some? body) (assoc "Content-Type" "application/json"))
                                 :body (when (some? body) (json/generate-string body))})
         text (http/body-string resp)
         json? (str/includes? (str (get-in resp [:headers "content-type"])) "json")
         parsed (if (and json? (not (str/blank? text))) (json/parse-string text) text)
         resp (assoc resp :body parsed)]
     (when-not (or (<= 200 (:status resp) 299) (contains? (set ok-statuses) (:status resp)))
       (throw (ex-info (str "Docker API " (str/upper-case (name method)) " " path ": "
                            (:status resp) " " (if (map? parsed) (get parsed "message") parsed))
                       {:status (:status resp) :path path :message (if (map? parsed) (get parsed "message") parsed)})))
     resp)))

(defn open
  "Connect and negotiate the API version. Returns a connection map for
   `request`; close it with `close`."
  [endpoint]
  (let [conn (connect endpoint)]
    (try
      (let [resp (raw-request conn {:method :get :path "/version"})
            server (when (= 200 (:status resp))
                     (get (json/parse-string (http/body-string resp)) "ApiVersion"))
            v (negotiate-version server)]
        (log/debug "Docker API version" v "(daemon" server ")")
        (assoc conn :api-version v :server-api-version server))
      (catch Exception e
        ((:close conn))
        (throw e)))))

(defn close [conn]
  (when-let [f (:close conn)] (f)))

(defmacro with-connection
  "Open a connection to `endpoint` as `sym` for `body`, then close it."
  [[sym endpoint] & body]
  `(let [~sym (open ~endpoint)]
     (try ~@body
          (finally (close ~sym)))))
