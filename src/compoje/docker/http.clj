(ns compoje.docker.http
  "Minimal HTTP/1.1 client over an InputStream/OutputStream pair, enough for
   the Docker Engine API on any transport (unix socket, TCP/TLS, ssh
   dial-stdio). Requests carry Content-Length; responses may use
   Content-Length, chunked encoding, or end at connection close."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream InputStream OutputStream)
           (java.nio.charset StandardCharsets)))

(defn- ascii ^bytes [^String s] (.getBytes s StandardCharsets/US_ASCII))

(defn encode-request
  "Bytes of an HTTP/1.1 request. `body` is a string or bytes (sent as is)."
  ^bytes [{:keys [method path headers body]}]
  (let [body (cond (nil? body) nil
                   (string? body) (.getBytes ^String body StandardCharsets/UTF_8)
                   :else body)
        headers (cond-> (merge {"Host" "docker"} headers)
                  body (assoc "Content-Length" (str (alength ^bytes body))))
        head (str (str/upper-case (name method)) " " path " HTTP/1.1\r\n"
                  (apply str (for [[k v] (sort-by key headers)] (str k ": " v "\r\n")))
                  "\r\n")
        out (ByteArrayOutputStream.)]
    (.write out (ascii head))
    (when body (.write out ^bytes body))
    (.toByteArray out)))

^:rct/test
(comment
  (String. (encode-request {:method :get :path "/_ping"}) "US-ASCII")
  ;=> "GET /_ping HTTP/1.1\r\nHost: docker\r\n\r\n"
  (String. (encode-request {:method :post :path "/v1.47/secrets/create"
                            :headers {"Content-Type" "application/json"}
                            :body "{}"}) "US-ASCII")
  ;=> "POST /v1.47/secrets/create HTTP/1.1\r\nContent-Length: 2\r\nContent-Type: application/json\r\nHost: docker\r\n\r\n{}"
  )

(defn- read-line-crlf
  "Read one line ending in CRLF (or LF); nil at end of stream."
  [^InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (= -1 b) (when (pos? (.length sb)) (str sb))
          (= 10 b) (let [s (str sb)] (if (str/ends-with? s "\r") (subs s 0 (dec (count s))) s))
          :else (do (.append sb (char b)) (recur)))))))

(defn- read-n ^bytes [^InputStream in n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (when (< off n)
        (let [r (.read in buf off (- n off))]
          (when (neg? r)
            (throw (ex-info "Docker API: connection closed in the middle of a response" {})))
          (recur (+ off r)))))
    buf))

(defn- read-chunked ^bytes [^InputStream in]
  (let [out (ByteArrayOutputStream.)]
    (loop []
      (let [size-line (or (read-line-crlf in)
                          (throw (ex-info "Docker API: bad chunked response" {})))
            size (Long/parseLong (str/trim (first (str/split size-line #";"))) 16)]
        (if (zero? size)
          ;; trailers until the empty line
          (loop [] (when-not (str/blank? (read-line-crlf in)) (recur)))
          (do (.write out (read-n in size))
              (read-line-crlf in)
              (recur)))))
    (.toByteArray out)))

(defn- read-to-end ^bytes [^InputStream in]
  (let [out (ByteArrayOutputStream.)]
    (.transferTo in out)
    (.toByteArray out)))

(defn read-response
  "Read one HTTP response from `in`: {:status :reason :headers :body bytes}.
   Header names are lower-cased."
  [^InputStream in]
  (let [status-line (or (read-line-crlf in)
                        (throw (ex-info "Docker API: connection closed before a response" {})))
        [_ status reason] (re-matches #"HTTP/1\.[01] (\d{3}) ?(.*)" status-line)
        _ (when-not status
            (throw (ex-info (str "Docker API: not an HTTP response: " status-line) {})))
        status (Long/parseLong status)
        headers (loop [h {}]
                  (let [line (read-line-crlf in)]
                    (if (str/blank? line)
                      h
                      (let [[k v] (str/split line #":" 2)]
                        (recur (assoc h (str/lower-case (str/trim k)) (str/trim (str v))))))))
        no-body? (or (< status 200) (#{204 304} status))
        body (cond
               no-body? (byte-array 0)
               (= "chunked" (some-> (get headers "transfer-encoding") str/lower-case))
               (read-chunked in)
               (get headers "content-length")
               (read-n in (Long/parseLong (get headers "content-length")))
               :else (read-to-end in))]
    {:status status :reason reason :headers headers :body body}))

(defn body-string [{:keys [^bytes body]}]
  (String. body StandardCharsets/UTF_8))

^:rct/test
(comment
  (let [r (read-response (java.io.ByteArrayInputStream.
                          (.getBytes "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nApi-Version: 1.57\r\n\r\nOKextra")))]
    [(:status r) (get-in r [:headers "api-version"]) (body-string r)])
  ;=> [200 "1.57" "OK"]
  (body-string (read-response (java.io.ByteArrayInputStream.
                               (.getBytes "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2;x=y\r\nde\r\n0\r\n\r\n"))))
  ;=> "abcde"
  (:status (read-response (java.io.ByteArrayInputStream. (.getBytes "HTTP/1.1 204 No Content\r\n\r\n"))))
  ;=> 204
  (body-string (read-response (java.io.ByteArrayInputStream.
                               (.getBytes "HTTP/1.0 200 OK\r\nConnection: close\r\n\r\nall of it"))))
  ;=> "all of it"
  )

(defn round-trip
  "Send `request` on `out` and read the response from `in`."
  [^InputStream in ^OutputStream out request]
  (.write out (encode-request request))
  (.flush out)
  (read-response in))
