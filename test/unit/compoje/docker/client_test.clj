(ns unit.compoje.docker.client-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [compoje.docker.client :as client]
            [unit.compoje.docker.fake-server :as fake]))

(defn- routes [{:keys [request-line]}]
  (condp re-find request-line
    #"GET /version " (fake/response 200 "{\"ApiVersion\":\"1.57\"}")
    #"GET /v1.47/info " (fake/response 200 "{\"Swarm\":{\"ControlAvailable\":true}}")
    #"GET /v1.47/chunked " (str "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n"
                                "5\r\n[1,2,\r\n2\r\n3]\r\n0\r\n\r\n")
    #"POST /v1.47/secrets/create[ ?]" (fake/response 201 "{\"ID\":\"abc\"}")
    #"GET /v1.47/secrets/nope " (fake/response 404 "{\"message\":\"secret nope not found\"}")
    #"DELETE /v1.47/gone " "HTTP/1.1 204 No Content\r\n\r\n"
    (fake/response 500 "{\"message\":\"unexpected\"}")))

(defn- endpoint [port]
  {:scheme :tcp :host "127.0.0.1" :port port :allow-insecure-tcp true :host-url "tcp://test"})

(deftest requests-over-one-connection
  (let [{:keys [port requests stop]} (fake/start! routes)]
    (try
      (client/with-connection [conn (endpoint port)]
        (testing "version negotiated down to what compoje speaks"
          (is (= "1.47" (:api-version conn)))
          (is (= "1.57" (:server-api-version conn))))
        (testing "JSON bodies are parsed"
          (is (true? (get-in (client/request conn :get "/info") [:body "Swarm" "ControlAvailable"]))))
        (testing "chunked responses"
          (is (= [1 2 3] (:body (client/request conn :get "/chunked")))))
        (testing "JSON request body and query string"
          (is (= {"ID" "abc"} (:body (client/request conn :post "/secrets/create"
                                                     {:body {"Name" "s" "Data" "eA=="}
                                                      :query {:x "a b"}}))))
          (let [req (last @requests)]
            (is (str/includes? (:request-line req) "/v1.47/secrets/create?x=a+b"))
            (is (= "application/json" (get-in req [:headers "content-type"])))
            (is (= "{\"Name\":\"s\",\"Data\":\"eA==\"}" (:body req)))))
        (testing "204 without a body"
          (is (= 204 (:status (client/request conn :delete "/gone")))))
        (testing "errors carry the daemon's message"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"GET /secrets/nope: 404 secret nope not found"
                                (client/request conn :get "/secrets/nope")))
          (is (= 404 (:status (client/request conn :get "/secrets/nope" {:ok-statuses #{404}}))))))
      (testing "all requests went over a single connection (keep-alive)"
        (is (= 7 (count @requests))))
      (finally (stop)))))

(deftest refuses-plain-tcp
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refusing plain TCP"
                        (client/connect {:scheme :tcp :host "127.0.0.1" :port 1 :host-url "tcp://x"}))))

(deftest connection-errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot connect to the Docker socket /nonexistent.sock"
                        (client/connect {:scheme :unix :path "/nonexistent.sock" :host-url "unix:///nonexistent.sock"}))))
