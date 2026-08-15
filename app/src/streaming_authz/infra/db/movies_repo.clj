(ns streaming-authz.infra.db.movies-repo
  (:require [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]))

(defn find-by-ids [datasource ids]
  (if (empty? ids)
    []
    (sql/query datasource
      (into [(str "SELECT id, title, synopsis FROM movies WHERE id IN ("
                  (str/join "," (repeat (count ids) "?"))
                  ")")]
            ids)
      {:builder-fn rs/as-unqualified-maps})))

(defn upsert! [datasource {:keys [id title synopsis]}]
  (sql/query datasource
    ["INSERT INTO movies (id, title, synopsis) VALUES (?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, synopsis = EXCLUDED.synopsis"
     id title synopsis]))
