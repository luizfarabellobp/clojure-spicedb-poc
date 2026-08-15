(ns streaming-authz.infra.db.movies-repo
  (:require [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]))

(defn find-by-ids [datasource ids]
  (if (empty? ids)
    []
    (sql/query datasource
      (into [(str "SELECT id, title, synopsis, genre, release_year, duration_minutes
                    FROM movies WHERE id IN ("
                  (str/join "," (repeat (count ids) "?"))
                  ")")]
            ids)
      {:builder-fn rs/as-unqualified-maps})))

(defn upsert! [datasource {:keys [id title synopsis genre release-year duration-minutes]}]
  (sql/query datasource
    ["INSERT INTO movies (id, title, synopsis, genre, release_year, duration_minutes)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (id) DO UPDATE SET
        title = EXCLUDED.title, synopsis = EXCLUDED.synopsis, genre = EXCLUDED.genre,
        release_year = EXCLUDED.release_year, duration_minutes = EXCLUDED.duration_minutes"
     id title synopsis genre release-year duration-minutes]))
