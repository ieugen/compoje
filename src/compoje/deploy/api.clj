(ns compoje.deploy.api
  "Deploy engine that talks to the Docker Swarm API directly (no docker CLI).

   Does what `docker stack deploy` does for the specs from
   compoje.deploy.swarm-convert: create missing networks, secrets and configs,
   create or update services, optionally prune services that left the stack,
   pin image digests (--resolve-image) and send registry credentials
   (--with-registry-auth). With dry-run only reads, and logs what it would do."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [compoje.deploy.interpolate :as interpolate]
            [compoje.files :as files]
            [compoje.deploy.registry-auth :as registry-auth]
            [compoje.deploy.swarm-convert :as convert]
            [compoje.docker.client :as client]
            [taoensso.timbre :as log])
  (:import (java.util Base64)))

(defn- filters [m] {:filters (json/generate-string m)})

(defn- b64 [^bytes bs] (.encodeToString (Base64/getEncoder) bs))

(defn- stack-label [stack] (str convert/namespace-label "=" stack))

(defn check-manager!
  [conn]
  (let [info (:body (client/request conn :get "/info"))]
    (when-not (get-in info ["Swarm" "ControlAvailable"])
      (throw (ex-info (str "The Docker daemon at " (get-in conn [:endpoint :host-url])
                           " is not a swarm manager (Swarm: " (get-in info ["Swarm" "LocalNodeState"]) ")")
                      {})))))

;; ------------------------------------------------------------------ networks

(defn- ensure-networks! [conn stack networks dry-run]
  (let [existing (set (map #(get % "Name")
                           (:body (client/request conn :get "/networks"
                                                  {:query (filters {"label" [(stack-label stack)]})}))))]
    (doseq [{:keys [name external spec]} networks]
      (cond
        external (when (= 404 (:status (client/request conn :get (str "/networks/" name) {:ok-statuses #{404}})))
                   (throw (ex-info (str "Network " name " is declared external but could not be found."
                                        " Create it with the overlay driver first.")
                                   {:network name})))
        (contains? existing name) nil
        dry-run (log/info "Would create network" name)
        :else (do (log/info "Creating network" name)
                  (client/request conn :post "/networks/create" {:body spec}))))))

;; ------------------------------------------------------------------ secrets and configs

(defn- find-object [conn kind name]
  (first (filter #(= name (get-in % ["Spec" "Name"]))
                 (:body (client/request conn :get (str "/" kind "s") {:query (filters {"name" [name]})})))))

(defn- ensure-objects!
  "Create or update the stack's secrets or configs (`kind` \"secret\" /
   \"config\"). `managed`: compoje's in-memory objects by key. Returns
   {key {:name :id}} for service references."
  [conn kind defs managed template-dir dry-run]
  (into {}
        (for [{:keys [key name external file labels]} defs]
          (let [m (get managed key)
                existing (find-object conn kind name)]
            [key
             {:name name
              :id (cond
                    ;; compoje-managed: the name holds a content hash, so an existing one has this content
                    (and m existing) (do (log/info "Using existing" kind name) (get existing "ID"))
                    m (if dry-run
                        (do (log/info "Would create" kind name) (str "<new " kind ">"))
                        (do (log/info "Creating" kind name)
                            (get-in (client/request conn :post (str "/" kind "s/create")
                                                    {:body {"Name" name "Labels" (:labels m)
                                                            "Data" (b64 (.getBytes ^String (:data m) "UTF-8"))}})
                                    [:body "ID"])))
                    external (or (get existing "ID")
                                 (if dry-run
                                   (do (log/warn kind name "is declared external but does not exist") "<missing>")
                                   (throw (ex-info (str (str/capitalize kind) " " name
                                                        " is declared external but does not exist")
                                                   {:name name}))))
                    :else
                    (let [path (fs/path template-dir (str file))
                          spec {"Name" name "Labels" labels "Data" (b64 (files/read-bytes path))}
                          ;; Docker returns a config's data, never a secret's
                          same-data? (= (get-in existing ["Spec" "Data"]) (get spec "Data"))
                          same-labels? (= (or (get-in existing ["Spec" "Labels"]) {}) (or labels {}))
                          changed-msg (str "The content of " kind " " name " (" file ") changed, but Docker "
                                           kind "s can't be changed. Give it a new name, e.g. with"
                                           " <% file-hash '" file "' %> in its name, or use"
                                           " x-compoje: {from: ...} / {template: ...}.")]
                      (cond
                        (and existing (= kind "config") same-data? same-labels?)
                        (do (log/info "Unchanged config" name) (get existing "ID"))
                        (and dry-run (not existing))
                        (do (log/info "Would create" kind name) (str "<new " kind ">"))
                        (and dry-run (= kind "config") (not same-data?))
                        (do (log/warn (str "Dry run: " changed-msg " The deploy would fail.")) (get existing "ID"))
                        dry-run
                        (do (log/info (str "Would update labels of " kind " " name
                                           (when (= kind "secret")
                                             " (Docker keeps the data; the deploy fails if the file changed)")))
                            (get existing "ID"))
                        existing (do (log/info (str "Updating labels of " kind " " name))
                                     ;; like docker stack deploy: the daemon refuses changed data
                                     (try
                                       (client/request conn :post (str "/" kind "s/" (get existing "ID") "/update")
                                                       {:query {:version (get-in existing ["Version" "Index"])}
                                                        :body spec})
                                       (catch clojure.lang.ExceptionInfo e
                                         (if (str/includes? (str (ex-message e)) "only updates to Labels are allowed")
                                           (throw (ex-info changed-msg {:name name} e))
                                           (throw e))))
                                     (get existing "ID"))
                        :else (do (log/info "Creating" kind name)
                                  (get-in (client/request conn :post (str "/" kind "s/create") {:body spec})
                                          [:body "ID"])))))}]))))

(defn- file-references [refs objects kind svc]
  (vec (for [{:keys [key target uid gid mode]} refs]
         (let [{:keys [name id]} (or (get objects key)
                                     (throw (ex-info (str "Service " svc " uses undefined " kind " " key) {:key key})))]
           {"File" {"Name" target "UID" uid "GID" gid "Mode" mode}
            (str (str/capitalize kind) "ID") id
            (str (str/capitalize kind) "Name") name}))))

;; ------------------------------------------------------------------ images

(defn- pin-image
  "Pin `image` to its digest like docker does; returns [image platforms] or
   nil when the registry can't be reached (docker then deploys unpinned)."
  [conn image auth-header]
  (let [resp (client/request conn :get (str "/distribution/" image "/json")
                             {:headers (cond-> {} auth-header (assoc "X-Registry-Auth" auth-header))
                              :ok-statuses #{401 403 404 500}})]
    (if-let [digest (get-in resp [:body "Descriptor" "digest"])]
      [(convert/familiar-image (str (first (str/split image #"@")) "@" digest))
       ;; like docker/cli: clear the arm architecture (nodes report armv7l etc.,
       ;; see docker/swarmkit#2294)
       ;; and, like current docker, skip unknown/unknown (attestation manifests)
       ;; and duplicates
       (->> (get-in resp [:body "Platforms"])
            (remove (fn [{:strs [architecture os]}] (or (= "unknown" os) (= "unknown" architecture))))
            (map (fn [{:strs [architecture os]}]
                   (cond-> {"OS" os}
                     (not= "arm" (some-> architecture str/lower-case)) (assoc "Architecture" architecture))))
            distinct
            vec)]
      (do (log/warn "image" image "could not be accessed on a registry to record its digest."
                    "Each node will access" image "independently, possibly leading to different nodes running different versions of the image.")
          nil))))

;; ------------------------------------------------------------------ services

(defn- with-image [spec [image platforms]]
  (cond-> (assoc-in spec ["TaskTemplate" "ContainerSpec" "Image"] image)
    (seq platforms) (assoc-in ["TaskTemplate" "Placement" "Platforms"] platforms)))

(defn- deploy-services!
  [conn {:keys [stack services secrets configs docker-config resolve-image with-registry-auth prune dry-run]}]
  (let [existing (into {} (map (juxt #(get-in % ["Spec" "Name"]) identity))
                       (:body (client/request conn :get "/services"
                                              {:query (filters {"label" [(stack-label stack)]})})))]
    (doseq [{:keys [name spec secret-refs config-refs]} services]
      (let [image (get-in spec ["TaskTemplate" "ContainerSpec" "Image"])
            auth (when with-registry-auth
                   (some-> (registry-auth/credentials docker-config image) registry-auth/header))
            old (get existing name)
            ;; like docker/cli: the image as written in the stack (the label), not
            ;; the shortened spec image (docker.io/nginx:1 vs nginx:1)
            same-tag? (= (get-in spec ["Labels" "com.docker.stack.image"])
                         (get-in old ["Spec" "Labels" "com.docker.stack.image"]))
            spec (cond-> spec
                   (seq secret-refs) (assoc-in ["TaskTemplate" "ContainerSpec" "Secrets"]
                                               (file-references secret-refs secrets "secret" name))
                   (seq config-refs) (assoc-in ["TaskTemplate" "ContainerSpec" "Configs"]
                                               (file-references config-refs configs "config" name)))
            pinned (case (str resolve-image)
                     "never" nil
                     "changed" (if (and old same-tag?)
                                 [(get-in old ["Spec" "TaskTemplate" "ContainerSpec" "Image"])
                                  (get-in old ["Spec" "TaskTemplate" "Placement" "Platforms"])]
                                 (pin-image conn image auth))
                     (pin-image conn image auth))
            spec (cond-> spec pinned (with-image pinned))
            headers (cond-> {} auth (assoc "X-Registry-Auth" auth))
            warn! (fn [resp] (doseq [w (get-in resp [:body "Warnings"])] (log/warn w)))]
        (cond
          (and old dry-run) (log/info "Would update service" name)
          dry-run (log/info "Would create service" name)
          old (do (log/info "Updating service" name (str "(id: " (get old "ID") ")"))
                  (warn! (client/request conn :post (str "/services/" (get old "ID") "/update")
                                         {:query {:version (get-in old ["Version" "Index"])}
                                          :headers headers
                                          :body (assoc-in spec ["TaskTemplate" "ForceUpdate"]
                                                          (get-in old ["Spec" "TaskTemplate" "ForceUpdate"] 0))})))
          :else (do (log/info "Creating service" name)
                    (warn! (client/request conn :post "/services/create" {:headers headers :body spec}))))))
    (when prune
      (let [wanted (set (map :name services))]
        (doseq [[n s] existing :when (not (wanted n))]
          (if dry-run
            (log/info "Would remove service" n)
            (do (log/info "Removing service" n)
                (client/request conn :delete (str "/services/" (get s "ID"))))))))))

;; ------------------------------------------------------------------ entry point

(defn deploy!
  "Deploy `stack-data` (parsed, managed objects externalized) through the
   Swarm API. `opts`: :endpoint, :docker (stack, prune, resolve-image,
   with-registry-auth), :objects (managed in-memory secrets/configs),
   :template-dir, :docker-config (client config.json map), :dry-run."
  [stack-data {:keys [endpoint docker objects template-dir docker-config dry-run getenv]}]
  (let [{:keys [stack prune resolve-image with-registry-auth]} docker
        ;; compoje's in-memory objects (x-compoje from/template), by definition key
        managed (fn [kind] (into {} (for [o objects :when (= kind (:kind o))] [(:key o) o])))
        getenv (or getenv #(System/getenv %))
        ;; ${VAR} substitution from the environment, like docker stack deploy
        stack-data (interpolate/interpolate
                    stack-data getenv
                    #(log/warn (str "The \"" % "\" variable is not set. Defaulting to a blank string.")))
        plan (convert/convert stack-data {:stack stack
                                          :template-dir template-dir
                                          :read-file #(slurp (str (fs/path template-dir %)))
                                          :getenv getenv})]
    (doseq [p (:ignored plan)]
      (log/warn "Ignoring unsupported option (as docker stack does):" (str/join "." p)))
    (client/with-connection [conn endpoint]
      (check-manager! conn)
      (ensure-networks! conn stack (:networks plan) dry-run)
      (let [secrets (ensure-objects! conn "secret" (:secrets plan) (managed :secret) template-dir dry-run)
            configs (ensure-objects! conn "config" (:configs plan) (managed :config) template-dir dry-run)]
        (deploy-services! conn {:stack stack :services (:services plan) :secrets secrets :configs configs
                                :docker-config docker-config :resolve-image (or resolve-image "always")
                                :with-registry-auth with-registry-auth :prune prune :dry-run dry-run})))))
