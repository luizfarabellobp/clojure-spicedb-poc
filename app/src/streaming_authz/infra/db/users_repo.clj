(ns streaming-authz.infra.db.users-repo
  (:require [next.jdbc.sql :as sql]))

(defn upsert! [datasource {:keys [id email display-name country]}]
  (sql/query datasource
    ["INSERT INTO users (id, email, display_name, country) VALUES (?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET
        email = EXCLUDED.email, display_name = EXCLUDED.display_name, country = EXCLUDED.country"
     id email display-name country]))
