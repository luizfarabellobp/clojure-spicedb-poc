(ns streaming-authz.domain.movie-service
  (:require [streaming-authz.domain.authz-client :as authz]
            [streaming-authz.infra.spicedb.mapper :as mapper]
            [streaming-authz.infra.db.movies-repo :as movies-repo]))

(defn can-view?
  ([system params] (can-view? system params nil))
  ([{:keys [spicedb-client]} {:keys [user-id movie-id]} context]
   (authz/check-permission spicedb-client
     (cond-> (merge (mapper/movie-resource movie-id)
                     {:permission "view" :subject-id (mapper/user-subject-id user-id)})
       context (assoc :context context)))))

(defn available-movies [{:keys [spicedb-client datasource]} {:keys [user-id]}]
  (let [ids (authz/lookup-resources spicedb-client
              {:resource-type "movie" :permission "view"
               :subject-id (mapper/user-subject-id user-id)})]
    (movies-repo/find-by-ids datasource ids)))
