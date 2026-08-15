(ns streaming-authz.infra.spicedb.mapper)

(defn movie-resource [movie-id]
  {:resource-type "movie" :resource-id movie-id})

(defn user-subject-id [user-id]
  user-id)
