(ns integration.docker-client-test
  "compoje.docker.client against the local Docker daemon (needs Docker):
   the unix socket, and `docker system dial-stdio`, which is what the ssh
   transport runs on the remote host."
  (:require [clojure.test :refer [deftest is testing]]
            [compoje.docker.client :as client]
            [compoje.docker.endpoint :as endpoint]))

(def unix {:scheme :unix :path "/var/run/docker.sock" :host-url "unix:///var/run/docker.sock"})

(deftest unix-socket
  (client/with-connection [conn unix]
    (testing "version negotiated"
      (is (re-matches #"1\.\d+" (:api-version conn))))
    (testing "several requests on one connection"
      (is (string? (get-in (client/request conn :get "/info") [:body "ID"])))
      (is (= "OK" (:body (client/request conn :get "/_ping"))))
      (is (map? (:body (client/request conn :get "/version")))))
    (testing "API errors (an endpoint every daemon has; /secrets needs a swarm manager)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"404 No such container: compoje-no-such-container"
                            (client/request conn :get "/containers/compoje-no-such-container/json"))))))

(deftest dial-stdio
  (client/with-connection [conn {:scheme :process
                                 :command ["docker" "system" "dial-stdio"]
                                 :host-url "dial-stdio"}]
    (is (= "OK" (:body (client/request conn :get "/_ping"))))
    (is (string? (get-in (client/request conn :get "/info") [:body "ID"])))))

(deftest resolved-default-endpoint
  (testing "with DOCKER_HOST pointing at the socket"
    (client/with-connection [conn (endpoint/resolve-endpoint {:env {"DOCKER_HOST" "unix:///var/run/docker.sock"}})]
      (is (= "OK" (:body (client/request conn :get "/_ping")))))))
