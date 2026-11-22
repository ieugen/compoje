(ns compoje.deploy.swarm-convert
  "Convert a compose stack (parsed, string keys) into Docker Swarm API specs,
   like `docker stack deploy` (docker/cli cli/compose/convert) does, for a
   supported subset of the compose file. Pure functions except `load-stack`
   (env files, bind paths and secret/config files are read by the caller-
   provided `read-file`).

   Unknown keys are an error listing every unsupported path; keys that
   docker stack itself ignores are reported as warnings."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [yamlstar.composer :as composer]
            [yamlstar.constructor :as constructor]
            [yamlstar.parser :as parser]
            [yamlstar.resolver :as resolver]))

;; ------------------------------------------------------------------ YAML

(defn- octal-ints
  "docker reads compose files as YAML 1.1, where 0440 is octal. yamlstar is
   YAML 1.2 (0440 = 440). Rewrite leading-zero integer scalars to their
   octal value, so file modes mean what docker would read."
  [node]
  (walk/postwalk (fn [n]
                   (if (and (map? n) (= :scalar (:kind n)) (= "!!int" (:tag n))
                            (re-matches #"[+]?0[0-7]+" (str (:value n))))
                     (assoc n :value (str (Long/parseLong (str/replace (:value n) "+" "") 8)))
                     n))
                 node))

(defn parse-stack
  "Parse a rendered compose stack. Integers with a leading zero are octal,
   as in YAML 1.1 (docker)."
  [text]
  (-> (parser/parse text nil) composer/compose resolver/resolve octal-ints constructor/construct))

^:rct/test
(comment
  (parse-stack "a: 0440\nb: 440\nc: '0440'\nd: 0\n") ;=> {"a" 288 "b" 440 "c" "0440" "d" 0}
  )

;; ------------------------------------------------------------------ units

(def ^:private duration-units
  {"ns" 1 "us" 1000 "µs" 1000 "ms" 1000000 "s" 1000000000 "m" 60000000000 "h" 3600000000000})

(defn duration-ns
  "Go/compose duration (\"1m30s\", \"500ms\", \"1.5h\") in nanoseconds.
   0 (number) is 0; other numbers are an error, as in docker."
  [d]
  (cond
    (nil? d) nil
    (and (number? d) (zero? d)) 0
    (number? d) (throw (ex-info (str "Duration " d " needs a unit, e.g. " d "s") {:duration d}))
    :else
    (let [s (str/trim (str d))
          parts (re-seq #"([0-9]*\.?[0-9]+)(ns|us|µs|ms|s|m|h)" s)]
      (when (or (empty? parts) (not= s (apply str (map first parts))))
        (throw (ex-info (str "Invalid duration: " d) {:duration d})))
      (long (reduce + (for [[_ n u] parts] (* (Double/parseDouble n) (duration-units u))))))))

^:rct/test
(comment
  (duration-ns "1m30s") ;=> 90000000000
  (duration-ns "500ms") ;=> 500000000
  (duration-ns "1.5h") ;=> 5400000000000
  (duration-ns "0s") ;=> 0
  (duration-ns 0) ;=> 0
  (try (duration-ns "10") (catch Exception e (ex-message e))) ;=> "Invalid duration: 10"
  )

(defn bytes-of
  "Memory size like docker's RAMInBytes: 1048576, \"256M\", \"64m\", \"4g\",
   \"1.5gb\" (binary multipliers)."
  [v]
  (cond
    (nil? v) nil
    (number? v) (long v)
    :else
    (let [[_ n unit] (or (re-matches #"(?i)\s*([0-9]*\.?[0-9]+)\s*([kmgtp]?)i?b?\s*" (str v))
                         (throw (ex-info (str "Invalid size: " v) {:size v})))
          mult ({"" 1 "k" 1024 "m" (* 1024 1024) "g" (* 1024 1024 1024)
                 "t" (* 1024 1024 1024 1024) "p" (* 1024 1024 1024 1024 1024)}
                (str/lower-case unit))]
      (long (* (Double/parseDouble n) mult)))))

^:rct/test
(comment
  (bytes-of "256M") ;=> 268435456
  (bytes-of "64m") ;=> 67108864
  (bytes-of "4g") ;=> 4294967296
  (bytes-of 1048576) ;=> 1048576
  (bytes-of "10k") ;=> 10240
  )

(defn nano-cpus [v]
  (when (some? v) (Math/round (* 1e9 (Double/parseDouble (str v))))))

^:rct/test
(comment
  (nano-cpus "0.5") ;=> 500000000
  (nano-cpus 4) ;=> 4000000000
  )

(defn shell-split
  "Split a command string like a POSIX shell (quotes and backslashes), as
   docker does for string `command`/`entrypoint`."
  [s]
  (loop [cs (seq s) cur nil quote nil out []]
    (if-let [c (first cs)]
      (cond
        (and (nil? quote) (Character/isWhitespace ^char c))
        (recur (rest cs) nil nil (cond-> out cur (conj cur)))
        (and (nil? quote) (#{\' \"} c)) (recur (rest cs) (or cur "") c out)
        (= quote c) (recur (rest cs) cur nil out)
        (and (= \\ c) (not= \' quote) (second cs)) (recur (drop 2 cs) (str cur (second cs)) quote out)
        :else (recur (rest cs) (str cur c) quote out))
      (do (when quote (throw (ex-info (str "Unbalanced quote in command: " s) {:command s})))
          (cond-> out cur (conj cur))))))

^:rct/test
(comment
  (shell-split "nginx -g 'daemon off;'") ;=> ["nginx" "-g" "daemon off;"]
  (shell-split "sh -c \"echo \\\"hi\\\" there\"") ;=> ["sh" "-c" "echo \"hi\" there"]
  (shell-split "a  b\\ c ''") ;=> ["a" "b c" ""]
  )

(defn- command-list [v] (cond (nil? v) nil (string? v) (shell-split v) :else (mapv str v)))

;; ------------------------------------------------------------------ validation

(def ^:private ignored-service-keys
  "Keys docker stack deploy ignores itself (with a warning)."
  #{"build" "cgroup_parent" "container_name" "depends_on" "devices" "domainname" "external_links" "ipc"
    "links" "mac_address" "network_mode" "pid" "privileged" "restart" "security_opt" "shm_size"
    "userns_mode"})

(def ^:private schema
  "Supported keys. A map value means nested keys are checked too; :any
   means anything below is accepted (free-form maps); :list-of checks each
   map item of a list."
  {"version" :any
   "services" {:each {"image" :any "command" :any "entrypoint" :any "environment" :any
                      "env_file" :any "labels" :any "hostname" :any "user" :any "working_dir" :any
                      "init" :any "stop_grace_period" :any "stop_signal" :any "tty" :any
                      "stdin_open" :any "read_only" :any "extra_hosts" :any "dns" :any
                      "dns_search" :any "cap_add" :any "cap_drop" :any "sysctls" :any "ulimits" :any
                      "ports" {:list-of {"target" :any "published" :any "protocol" :any "mode" :any}}
                      "networks" :any "configs" :any "secrets" :any
                      "volumes" {:list-of {"type" :any "source" :any "target" :any "read_only" :any
                                           "bind" {"propagation" :any}
                                           "volume" {"nocopy" :any}
                                           "tmpfs" {"size" :any}
                                           "consistency" :any}}
                      "healthcheck" {"test" :any "interval" :any "timeout" :any "retries" :any
                                     "start_period" :any "disable" :any}
                      "logging" {"driver" :any "options" :any}
                      "deploy" {"mode" :any "replicas" :any "endpoint_mode" :any "labels" :any
                                "resources" {"limits" {"cpus" :any "memory" :any "pids" :any}
                                             "reservations" {"cpus" :any "memory" :any}}
                                "restart_policy" {"condition" :any "delay" :any "max_attempts" :any
                                                  "window" :any}
                                "placement" {"constraints" :any "preferences" :any
                                             "max_replicas_per_node" :any}
                                "update_config" {"parallelism" :any "delay" :any "failure_action" :any
                                                 "monitor" :any "max_failure_ratio" :any "order" :any}
                                "rollback_config" {"parallelism" :any "delay" :any "failure_action" :any
                                                   "monitor" :any "max_failure_ratio" :any "order" :any}}}}
   "networks" {:each {"name" :any "driver" :any "driver_opts" :any "attachable" :any "internal" :any
                      "labels" :any "external" :any
                      "ipam" {"driver" :any "config" {:list-of {"subnet" :any "gateway" :any "ip_range" :any}}}}}
   "volumes" {:each {"name" :any "driver" :any "driver_opts" :any "labels" :any "external" :any}}
   "secrets" {:each {"file" :any "name" :any "external" :any "labels" :any}}
   "configs" {:each {"file" :any "name" :any "external" :any "labels" :any}}})

(defn- check [spec v path]
  (cond
    (= :any spec) []
    (and (map? spec) (:each spec)) (if (map? v) (mapcat (fn [[k x]] (check (:each spec) x (conj path k))) v) [])
    (and (map? spec) (:list-of spec)) (if (sequential? v)
                                        (mapcat (fn [i x] (if (map? x) (check (:list-of spec) x (conj path i)) []))
                                                (range) v)
                                        [])
    (map? spec) (if (map? v)
                  (mapcat (fn [[k x]]
                            (cond
                              (str/starts-with? (str k) "x-") []
                              (contains? spec k) (check (spec k) x (conj path k))
                              :else [(conj path k)]))
                          v)
                  [])
    :else []))

(defn validate
  "{:unsupported [path ...] :ignored [path ...]} for a parsed stack."
  [stack]
  (let [paths (sort-by #(str/join "." %) (check schema stack []))
        ignored? #(and (= "services" (first %)) (= 3 (count %)) (ignored-service-keys (nth % 2)))]
    {:unsupported (vec (remove ignored? paths))
     :ignored (vec (filter ignored? paths))}))

^:rct/test
(comment
  (validate {"x-compoje" {} "services" {"a" {"image" "i" "build" "." "privileged" true "gpus" 1
                                             "deploy" {"resources" {"reservations" {"devices" []}}}}}
             "include" []})
  ;=> {:unsupported [["include"] ["services" "a" "deploy" "resources" "reservations" "devices"] ["services" "a" "gpus"]] :ignored [["services" "a" "build"] ["services" "a" "privileged"]]}
  )

;; ------------------------------------------------------------------ naming

(def namespace-label "com.docker.stack.namespace")

(defn scoped [stack n] (str stack "_" n))

(defn object-name
  "Docker name of a top-level network/volume/secret/config definition."
  [stack k definition]
  (or (get definition "name")
      (if (get definition "external") (str k) (scoped stack k))))

^:rct/test
(comment
  (object-name "s" "db" {}) ;=> "s_db"
  (object-name "s" "db" {"name" "db-v2"}) ;=> "db-v2"
  (object-name "s" "host" {"external" true}) ;=> "host"
  )

(defn familiar-image
  "Docker's short form of an image reference, as the docker CLI sends it:
   docker.io/library/nginx:stable -> nginx:stable, docker.io/me/app -> me/app.
   Other registries are left as they are."
  [image]
  (let [s (str image)
        hub (some #(when (str/starts-with? s %) %) ["docker.io/" "index.docker.io/" "registry-1.docker.io/"])
        s (if hub (subs s (count hub)) s)]
    (if (and hub (str/starts-with? s "library/") (not (str/includes? (subs s 8) "/")))
      (subs s 8)
      s)))

^:rct/test
(comment
  (familiar-image "docker.io/nginx:stable") ;=> "nginx:stable"
  (familiar-image "docker.io/library/nginx@sha256:ab") ;=> "nginx@sha256:ab"
  (familiar-image "docker.io/me/app:1") ;=> "me/app:1"
  (familiar-image "nginx") ;=> "nginx"
  (familiar-image "library/nginx") ;=> "library/nginx"
  (familiar-image "ghcr.io/org/app:2") ;=> "ghcr.io/org/app:2"
  )

;; ------------------------------------------------------------------ service pieces

(defn- kv-list
  "Map or [\"k=v\"] list as [[k v]...]; a missing value is nil."
  [v sep]
  (cond
    (map? v) (mapv (fn [[k x]] [(str k) (some-> x str)]) v)
    (sequential? v) (mapv #(let [[k x] (str/split (str %) (re-pattern (java.util.regex.Pattern/quote sep)) 2)] [k x]) v)
    :else []))

(defn parse-env-file
  "KEY=VALUE lines; blank lines and # comments skipped."
  [text]
  (into [] (for [line (str/split-lines text)
                 :let [l (str/trim line)]
                 :when (and (seq l) (not (str/starts-with? l "#")))]
             (let [[k v] (str/split l #"=" 2)] [(str/trim k) v]))))

(defn env-list
  "Sorted [\"K=V\"] from env_file entries (lower precedence) and environment.
   Keys without a value take it from `getenv`, or are left out."
  [env-file-entries env getenv]
  (let [entries (concat env-file-entries (kv-list env "="))
        m (reduce (fn [m [k v]]
                    (if (some? v)
                      (assoc m k v)
                      (if-let [e (getenv k)] (assoc m k e) (dissoc m k))))
                  {} entries)]
    (vec (sort (map (fn [[k v]] (str k "=" v)) m)))))

^:rct/test
(comment
  (env-list [["FROM_FILE" "1"] ["SHARED" "file"]] {"B" "2" "A" 1 "SHARED" "env"} {})
  ;=> ["A=1" "B=2" "FROM_FILE=1" "SHARED=env"]
  (env-list [] ["X=1" "Y=two words" "HOME"] {"HOME" "/root"}) ;=> ["HOME=/root" "X=1" "Y=two words"]
  )

(defn- port-range [s]
  (let [[a b] (str/split (str s) #"-")]
    (if b (range (parse-long a) (inc (parse-long b))) [(parse-long a)])))

(defn ports
  "EndpointSpec Ports from short (\"8080:80\", \"9000-9001:9000-9001/udp\")
   and long syntax."
  [ps]
  (vec
   (mapcat
    (fn [p]
      (if (map? p)
        [(cond-> {"Protocol" (str (get p "protocol" "tcp"))
                  "TargetPort" (long (get p "target"))
                  "PublishMode" (str (get p "mode" "ingress"))}
           (get p "published") (assoc "PublishedPort" (long (parse-long (str (get p "published"))))))]
        (let [[spec proto] (str/split (str p) #"/" 2)
              parts (str/split spec #":")
              _ (when (> (count parts) 2)
                  (throw (ex-info (str "Port " p ": a host IP is not supported in swarm mode") {:port p})))
              [published target] (if (= 2 (count parts)) parts [nil (first parts)])
              targets (port-range target)
              publisheds (when published (port-range published))]
          (when (and publisheds (not= (count targets) (count publisheds)))
            (throw (ex-info (str "Port " p ": ranges of different length") {:port p})))
          (for [[i t] (map-indexed vector targets)]
            (cond-> {"Protocol" (or proto "tcp") "TargetPort" t "PublishMode" "ingress"}
              publisheds (assoc "PublishedPort" (nth publisheds i)))))))
    ps)))

^:rct/test
(comment
  (ports ["8081:80" {"target" 443 "published" 8443 "protocol" "tcp" "mode" "host"} "9000-9001:9000-9001/udp" "53/udp"])
  ;=> [{"Protocol" "tcp" "TargetPort" 80 "PublishMode" "ingress" "PublishedPort" 8081}
  ;    {"Protocol" "tcp" "TargetPort" 443 "PublishMode" "host" "PublishedPort" 8443}
  ;    {"Protocol" "udp" "TargetPort" 9000 "PublishMode" "ingress" "PublishedPort" 9000}
  ;    {"Protocol" "udp" "TargetPort" 9001 "PublishMode" "ingress" "PublishedPort" 9001}
  ;    {"Protocol" "udp" "TargetPort" 53 "PublishMode" "ingress"}]
  )

(defn- extra-hosts [v]
  (vec (for [[host ip] (if (map? v)
                         (map (fn [[h i]] [(str h) (str i)]) v)
                         (map #(str/split (str %) #"[:=]" 2) v))]
         (str ip " " host))))

(defn- capabilities [cs]
  (when (seq cs)
    (mapv #(let [c (str/upper-case (str %))] (if (or (= "ALL" c) (str/starts-with? c "CAP_")) c (str "CAP_" c))) cs)))

(defn- ulimits [m]
  (when (seq m)
    (vec (for [[n v] (sort-by key m)]
           (if (map? v)
             {"Name" (str n) "Hard" (long (get v "hard")) "Soft" (long (get v "soft"))}
             {"Name" (str n) "Hard" (long v) "Soft" (long v)})))))

(defn- health-spec [{:strs [test interval timeout retries start_period disable] :as hc}]
  (when hc
    (if (or disable (= ["NONE"] test))
      {"Test" ["NONE"]}
      (cond-> {}
        test (assoc "Test" (if (string? test) ["CMD-SHELL" test] (mapv str test)))
        interval (assoc "Interval" (duration-ns interval))
        timeout (assoc "Timeout" (duration-ns timeout))
        start_period (assoc "StartPeriod" (duration-ns start_period))
        retries (assoc "Retries" (long retries))))))

(defn- update-config [c]
  (when c
    (cond-> {"Parallelism" (long (get c "parallelism" 1))}
      (get c "delay") (assoc "Delay" (duration-ns (get c "delay")))
      (get c "failure_action") (assoc "FailureAction" (get c "failure_action"))
      (get c "monitor") (assoc "Monitor" (duration-ns (get c "monitor")))
      (some? (get c "max_failure_ratio")) (assoc "MaxFailureRatio" (double (get c "max_failure_ratio")))
      (get c "order") (assoc "Order" (get c "order")))))

(defn- resources [{:strs [limits reservations]}]
  (let [res (fn [m pids?]
              (when m
                (cond-> {}
                  (get m "cpus") (assoc "NanoCPUs" (nano-cpus (get m "cpus")))
                  (get m "memory") (assoc "MemoryBytes" (bytes-of (get m "memory")))
                  (and pids? (get m "pids")) (assoc "Pids" (long (get m "pids"))))))]
    (cond-> {}
      limits (assoc "Limits" (res limits true))
      reservations (assoc "Reservations" (res reservations false)))))

(defn- placement [{:strs [constraints preferences max_replicas_per_node] :as p}]
  (when p
    (cond-> {}
      constraints (assoc "Constraints" (mapv str constraints))
      preferences (assoc "Preferences" (mapv (fn [pref] {"Spread" {"SpreadDescriptor" (str (get pref "spread"))}})
                                             preferences))
      max_replicas_per_node (assoc "MaxReplicas" (long max_replicas_per_node)))))

(defn- restart-policy [{:strs [condition delay max_attempts window] :as r}]
  (when r
    (cond-> {}
      condition (assoc "Condition" condition)
      delay (assoc "Delay" (duration-ns delay))
      (some? max_attempts) (assoc "MaxAttempts" (long max_attempts))
      window (assoc "Window" (duration-ns window)))))

(defn- service-networks
  "TaskTemplate Networks: aliases (given + service name), sorted by target."
  [stack svc-name nets network-defs]
  (let [entries (cond
                  (nil? nets) [["default" nil]]
                  (map? nets) (map (fn [[k v]] [(str k) v]) nets)
                  :else (map (fn [k] [(str k) nil]) nets))]
    (->> entries
         (map (fn [[k v]]
                (let [target (if (= "default" k)
                               (object-name stack "default" (get network-defs "default"))
                               (if-let [d (get network-defs k)]
                                 (object-name stack k d)
                                 (throw (ex-info (str "Service " svc-name " uses undefined network " k) {:network k}))))]
                  {"Target" target
                   "Aliases" (conj (vec (map str (get v "aliases"))) svc-name)})))
         (sort-by #(get % "Target"))
         vec)))

(defn- mount
  "One Mount for a short (\"src:dst:ro\") or long volume entry."
  [stack svc-name v volume-defs template-dir]
  (let [{:strs [type source target read_only volume tmpfs bind]}
        (if (map? v)
          v
          (let [[a b mode] (str/split (str v) #":")]
            (cond-> (if b {"source" a "target" b} {"target" a})
              true (assoc "type" (cond (nil? b) "volume"
                                       (re-find #"^[./~]" a) "bind"
                                       :else "volume"))
              (and mode (re-find #"(^|,)ro(,|$)" mode)) (assoc "read_only" true)
              (and mode (re-find #"(^|,)nocopy(,|$)" mode)) (assoc "volume" {"nocopy" true}))))
        type (or type "volume")]
    (case type
      "bind" (cond-> {"Type" "bind"
                      "Source" (let [p (str source)
                                     p (if (str/starts-with? p "~") (str (System/getProperty "user.home") (subs p 1)) p)]
                                 (str (fs/normalize (fs/absolutize (if (fs/absolute? p) p (fs/path template-dir p))))))
                      "Target" (str target)}
               read_only (assoc "ReadOnly" true)
               (get bind "propagation") (assoc "BindOptions" {"Propagation" (get bind "propagation")}))
      "tmpfs" (cond-> {"Type" "tmpfs" "Target" (str target)}
                (get tmpfs "size") (assoc "TmpfsOptions" {"SizeBytes" (bytes-of (get tmpfs "size"))}))
      "volume" (if (nil? source)
                 (cond-> {"Type" "volume" "Target" (str target)} read_only (assoc "ReadOnly" true))
                 (let [d (or (get volume-defs source)
                             (throw (ex-info (str "Service " svc-name " uses undefined volume " source) {:volume source})))
                       external? (get d "external")
                       opts (cond-> {}
                              (get volume "nocopy") (assoc "NoCopy" true)
                              (not external?) (assoc "Labels" (merge (get d "labels") {namespace-label stack}))
                              (and (not external?) (get d "driver"))
                              (assoc "DriverConfig" (cond-> {"Name" (get d "driver")}
                                                      (get d "driver_opts") (assoc "Options" (get d "driver_opts")))))]
                   (cond-> {"Type" "volume" "Source" (object-name stack source d) "Target" (str target)}
                     read_only (assoc "ReadOnly" true)
                     (seq opts) (assoc "VolumeOptions" opts))))
      (throw (ex-info (str "Service " svc-name ": unsupported volume type " type) {:type type})))))

(defn- file-refs
  "Secret/config references of a service: [{:key :target :uid :gid :mode}].
   Short syntax targets default to the key (docker mounts it under /run/secrets
   or / respectively)."
  [refs]
  (vec (for [r refs]
         (if (map? r)
           {:key (str (get r "source")) :target (str (get r "target" (get r "source")))
            :uid (str (get r "uid" "0")) :gid (str (get r "gid" "0")) :mode (long (get r "mode" 0444))}
           {:key (str r) :target (str r) :uid "0" :gid "0" :mode 0444}))))

(defn service-spec
  "ServiceSpec for service `svc-name`. Secret/config references are left as
   {:secret-refs/:config-refs} for the engine to fill in object IDs.
   `opts`: :stack, :template-dir, :network-defs, :volume-defs, :read-file,
   :getenv."
  [svc-name svc {:keys [stack template-dir network-defs volume-defs read-file getenv]}]
  (let [{:strs [image entrypoint command labels hostname user working_dir init stop_grace_period
                stop_signal tty stdin_open read_only extra_hosts dns dns_search cap_add cap_drop
                sysctls environment env_file healthcheck logging deploy networks volumes]} svc
        deploy (or deploy {})
        env-files (mapcat #(parse-env-file (read-file %)) (if (string? env_file) [env_file] env_file))
        container (cond-> {"Image" (familiar-image image)
                           "Labels" (merge (into {} (kv-list labels "=")) {namespace-label stack})
                           ;; the docker CLI always sends (empty) privileges
                           "Privileges" {"CredentialSpec" nil "SELinuxContext" nil "NoNewPrivileges" false}}
                    entrypoint (assoc "Command" (command-list entrypoint))
                    command (assoc "Args" (command-list command))
                    hostname (assoc "Hostname" (str hostname))
                    (or environment env_file) (assoc "Env" (env-list env-files environment getenv))
                    working_dir (assoc "Dir" (str working_dir))
                    user (assoc "User" (str user))
                    (some? init) (assoc "Init" (boolean init))
                    stop_signal (assoc "StopSignal" (str stop_signal))
                    tty (assoc "TTY" true)
                    stdin_open (assoc "OpenStdin" true)
                    read_only (assoc "ReadOnly" true)
                    (seq volumes) (assoc "Mounts" (mapv #(mount stack svc-name % volume-defs template-dir) volumes))
                    stop_grace_period (assoc "StopGracePeriod" (duration-ns stop_grace_period))
                    healthcheck (assoc "Healthcheck" (health-spec healthcheck))
                    extra_hosts (assoc "Hosts" (extra-hosts extra_hosts))
                    (or dns dns_search) (assoc "DNSConfig" (cond-> {}
                                                             dns (assoc "Nameservers" (mapv str (if (string? dns) [dns] dns)))
                                                             dns_search (assoc "Search" (mapv str (if (string? dns_search) [dns_search] dns_search)))))
                    sysctls (assoc "Sysctls" (into {} (kv-list sysctls "=")))
                    cap_add (assoc "CapabilityAdd" (capabilities cap_add))
                    cap_drop (assoc "CapabilityDrop" (capabilities cap_drop))
                    (get svc "ulimits") (assoc "Ulimits" (ulimits (get svc "ulimits"))))
        mode (get deploy "mode" "replicated")
        ;; like the docker CLI, Resources and Placement are always sent (possibly empty)
        task (cond-> {"ContainerSpec" container
                      "Networks" (service-networks stack svc-name networks network-defs)
                      "Resources" (resources (get deploy "resources" {}))
                      "Placement" (or (placement (get deploy "placement")) {})}
               (get deploy "restart_policy") (assoc "RestartPolicy" (restart-policy (get deploy "restart_policy")))
               logging (assoc "LogDriver" (cond-> {"Name" (get logging "driver")}
                                            (get logging "options") (assoc "Options" (get logging "options")))))
        ports (ports (get svc "ports"))]
    {:name (scoped stack svc-name)
     :spec (cond-> {"Name" (scoped stack svc-name)
                    "Labels" (merge (into {} (kv-list (get deploy "labels") "="))
                                    {namespace-label stack "com.docker.stack.image" (str image)})
                    "TaskTemplate" task
                    "Mode" (case mode
                             "global" {"Global" {}}
                             "replicated" {"Replicated" {"Replicas" (long (get deploy "replicas" 1))}}
                             (throw (ex-info (str "Service " svc-name ": unsupported deploy mode " mode) {:mode mode})))}
             (get deploy "update_config") (assoc "UpdateConfig" (update-config (get deploy "update_config")))
             (get deploy "rollback_config") (assoc "RollbackConfig" (update-config (get deploy "rollback_config")))
             (or (seq ports) (get deploy "endpoint_mode"))
             (assoc "EndpointSpec" (cond-> {}
                                     (get deploy "endpoint_mode") (assoc "Mode" (get deploy "endpoint_mode"))
                                     (seq ports) (assoc "Ports" ports))))
     :secret-refs (file-refs (get svc "secrets"))
     :config-refs (file-refs (get svc "configs"))}))

;; ------------------------------------------------------------------ whole stack

(defn- network-spec [stack k d]
  {:name (object-name stack k d)
   :external (boolean (get d "external"))
   :spec (cond-> {"Name" (object-name stack k d)
                  "Driver" (get d "driver" "overlay")
                  "Labels" (merge (get d "labels") {namespace-label stack})
                  "CheckDuplicate" true}
           (get d "driver_opts") (assoc "Options" (get d "driver_opts"))
           (some? (get d "attachable")) (assoc "Attachable" (boolean (get d "attachable")))
           (some? (get d "internal")) (assoc "Internal" (boolean (get d "internal")))
           (get d "ipam") (assoc "IPAM" (cond-> {}
                                          (get-in d ["ipam" "driver"]) (assoc "Driver" (get-in d ["ipam" "driver"]))
                                          (get-in d ["ipam" "config"])
                                          (assoc "Config" (mapv (fn [c] (cond-> {}
                                                                          (get c "subnet") (assoc "Subnet" (get c "subnet"))
                                                                          (get c "gateway") (assoc "Gateway" (get c "gateway"))
                                                                          (get c "ip_range") (assoc "IPRange" (get c "ip_range"))))
                                                                (get-in d ["ipam" "config"]))))))})

(defn- object-spec
  "Secret/config spec: {:key :name :external :file :labels}; data is read by the engine."
  [stack k d]
  {:key (str k)
   :name (object-name stack k d)
   :external (boolean (get d "external"))
   :file (get d "file")
   :labels (merge (get d "labels") {namespace-label stack})})

(defn convert
  "Convert a parsed stack (managed x-compoje objects already externalized)
   into {:networks [...] :secrets [...] :configs [...] :services [...]
   :ignored [...]}. Throws on unsupported keys."
  [stack-data {:keys [stack] :as opts}]
  (let [{:keys [unsupported ignored]} (validate stack-data)
        _ (when (seq unsupported)
            (throw (ex-info (str "The docker-api engine does not support these compose keys (use the docker-cli engine):\n"
                                 (str/join "\n" (map #(str "  " (str/join "." %)) unsupported)))
                            {:unsupported unsupported})))
        services (get stack-data "services")
        uses-default? (some (fn [[_ s]] (nil? (get s "networks"))) services)
        network-defs (cond-> (or (get stack-data "networks") {})
                       uses-default? (update "default" #(or % {})))
        opts (assoc opts :network-defs network-defs :volume-defs (or (get stack-data "volumes") {}))]
    {:networks (vec (for [[k d] (sort-by key network-defs)] (network-spec stack (str k) (or d {}))))
     :secrets (vec (for [[k d] (sort-by key (get stack-data "secrets"))] (object-spec stack k d)))
     :configs (vec (for [[k d] (sort-by key (get stack-data "configs"))] (object-spec stack k d)))
     :services (vec (for [[k s] (sort-by key services)] (service-spec (str k) s opts)))
     :ignored ignored}))
