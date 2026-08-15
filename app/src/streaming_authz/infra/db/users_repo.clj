(ns streaming-authz.infra.db.users-repo
  (:require [next.jdbc.sql :as sql]))

(defn upsert! [datasource {:keys [id email display-name]}]
  (sql/query datasource
    ["INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET email = EXCLUDED.email, display_name = EXCLUDED.display_name"
     id email display-name]))
