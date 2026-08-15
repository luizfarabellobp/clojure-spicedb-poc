(ns streaming-authz.core
  (:require [com.stuartsierra.component :as component]
            [streaming-authz.config :as config]
            [streaming-authz.infra.spicedb.client :as spicedb-client]
            [streaming-authz.infra.db.datasource-component :as datasource]
            [streaming-authz.infra.http.pedestal-component :as pedestal-component]
            [streaming-authz.infra.seed.bootstrap :as bootstrap]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn system [config]
  (component/system-map
    :spicedb-client (spicedb-client/new-spicedb-client (:spicedb config))
    :datasource     (datasource/new-datasource-component (:db-app config))
    :pedestal       (component/using
                       (pedestal-component/new-pedestal-component (:server config) (:auth config))
                       [:spicedb-client :datasource])))

(defn -main [& _args]
  (let [config (config/load-config)
        sys    (component/start (system config))]
    (log/info "Seeding fixed scenario...")
    (bootstrap/seed! (:spicedb-client sys) (:datasource (:datasource sys)))
    (log/info "streaming-authz started on port" (get-in config [:server :port]))
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. (fn []
                 (log/info "Shutting down...")
                 (component/stop sys))))
    sys))
