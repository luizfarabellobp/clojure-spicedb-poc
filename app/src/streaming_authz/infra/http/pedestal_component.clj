(ns streaming-authz.infra.http.pedestal-component
  (:require [com.stuartsierra.component :as component]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route :as route]
            [streaming-authz.infra.http.routes :as routes]
            [clojure.tools.logging :as log]))

(defrecord PedestalComponent [port auth-config spicedb-client datasource server]
  component/Lifecycle
  (start [this]
    (log/info "Starting HTTP server on port" port)
    (let [system {:spicedb-client spicedb-client :datasource (:datasource datasource)}
          service-map (-> {::http/routes (routes/routes system auth-config)
                            ::http/type :jetty
                            ::http/host "0.0.0.0"
                            ::http/port port
                            ::http/join? false}
                           (http/default-interceptors)
                           (update ::http/interceptors conj (body-params/body-params))
                           (update ::http/interceptors conj route/query-params)
                           http/create-server
                           http/start)]
      (assoc this :server service-map)))
  (stop [this]
    (when server
      (log/info "Stopping HTTP server")
      (http/stop server))
    (assoc this :server nil)))

(defn new-pedestal-component [{:keys [port]} auth-config]
  (map->PedestalComponent {:port port :auth-config auth-config}))
