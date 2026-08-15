(ns streaming-authz.infra.db.datasource-component
  (:require [com.stuartsierra.component :as component]
            [next.jdbc :as jdbc]
            [clojure.tools.logging :as log]))

(defrecord DatasourceComponent [jdbc-url datasource]
  component/Lifecycle
  (start [this]
    (log/info "Connecting to app Postgres database")
    (assoc this :datasource (jdbc/get-datasource jdbc-url)))
  (stop [this]
    (assoc this :datasource nil)))

(defn new-datasource-component [{:keys [jdbc-url]}]
  (map->DatasourceComponent {:jdbc-url jdbc-url}))
