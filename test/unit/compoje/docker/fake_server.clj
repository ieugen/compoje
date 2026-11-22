(ns unit.compoje.docker.fake-server
  "Tiny in-process HTTP server for transport tests: plain TCP or TLS (with
   client certificate check). Serves canned responses per path, keeps the
   connection open between requests (keep-alive)."
  (:require [clojure.string :as str])
  (:import (java.io BufferedInputStream InputStream OutputStream)
           (java.net ServerSocket Socket)
           (javax.net.ssl SSLContext)))

(defn- read-request
  "Read request line + headers + body; nil at end of stream."
  [^InputStream in]
  (let [line (fn [] (let [sb (StringBuilder.)]
                      (loop []
                        (let [b (.read in)]
                          (cond (= -1 b) (when (pos? (.length sb)) (str sb))
                                (= 10 b) (str/trimr (str sb))
                                :else (do (.append sb (char b)) (recur)))))))]
    (when-let [request-line (line)]
      (let [headers (loop [h {}]
                      (let [l (line)]
                        (if (str/blank? l) h
                            (let [[k v] (str/split l #":" 2)]
                              (recur (assoc h (str/lower-case k) (str/trim v)))))))
            n (some-> (get headers "content-length") Long/parseLong)
            body (when n (String. (.readNBytes in (int n)) "UTF-8"))]
        {:request-line request-line :headers headers :body body}))))

(defn start!
  "Start a server. `routes`: fn of request map -> raw response string.
   `ssl-context`: serve TLS and require a client certificate.
   Returns {:port :requests (atom) :stop fn}."
  [routes & [^SSLContext ssl-context]]
  (let [^ServerSocket server (if ssl-context
                               (doto (.createServerSocket (.getServerSocketFactory ssl-context) 0)
                                 (.setNeedClientAuth true))
                               (ServerSocket. 0))
        requests (atom [])
        worker (future
                 (try
                   (loop []
                     (let [^Socket s (.accept server)]
                       (future
                         (with-open [s s]
                           (let [in (BufferedInputStream. (.getInputStream s))
                                 ^OutputStream out (.getOutputStream s)]
                             (loop []
                               (when-let [req (read-request in)]
                                 (swap! requests conj req)
                                 (.write out (.getBytes ^String (routes req) "UTF-8"))
                                 (.flush out)
                                 (recur))))))
                       (recur)))
                   (catch Exception _ nil)))]
    {:port (.getLocalPort server)
     :requests requests
     :stop #(do (.close server) (future-cancel worker))}))

(defn response
  ([status body] (response status body "application/json"))
  ([status body content-type]
   (str "HTTP/1.1 " status " X\r\nContent-Type: " content-type
        "\r\nContent-Length: " (count (.getBytes ^String body "UTF-8")) "\r\n\r\n" body)))
