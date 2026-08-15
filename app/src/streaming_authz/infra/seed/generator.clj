(ns streaming-authz.infra.seed.generator
  (:refer-clojure :exclude [run!])
  (:require [streaming-authz.config :as config]
            [streaming-authz.domain.authz-client :as authz]
            [streaming-authz.infra.db.movies-repo :as movies-repo]
            [streaming-authz.infra.db.users-repo :as users-repo]
            [streaming-authz.infra.spicedb.client :as spicedb-client]
            [streaming-authz.infra.db.datasource-component :as datasource]
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]))

(def profile->counts
  {:small  {:users 20   :movies 15  :relations-per-user 3}
   :medium {:users 200  :movies 80  :relations-per-user 5}
   :large  {:users 2000 :movies 300 :relations-per-user 8}})

(def ^:private countries ["BR" "US" "AR" "PT" "MX"])
(def ^:private genres ["Ação" "Comédia" "Drama" "Ficção Científica" "Documentário" "Terror"])

(defn- gen-users [n]
  (mapv (fn [i] {:id (str "gen-user-" i)
                 :email (str "gen-user-" i "@example.com")
                 :display-name (str "Generated User " i)
                 :country (nth countries (mod i (count countries)))})
        (range n)))

(defn- gen-movies [n]
  (mapv (fn [i] {:id (str "gen-movie-" i)
                 :title (str "Generated Movie " i)
                 :synopsis "Filme gerado para teste de volume."
                 :genre (nth genres (mod i (count genres)))
                 :release-year (+ 1990 (mod i 35))
                 :duration-minutes (+ 75 (mod (* i 7) 90))})
        (range n)))

(defn- sample-distinct [^java.util.Random rng movies n]
  (let [al (java.util.ArrayList. ^java.util.Collection movies)]
    (java.util.Collections/shuffle al rng)
    (vec (take n al))))

(defn- gen-relations [rng users movies relations-per-user]
  (vec (for [user users
             movie (sample-distinct rng movies relations-per-user)]
         {:resource-type "movie" :resource-id (:id movie)
          :relation "direct_viewer" :subject-type "user" :subject-id (:id user)})))

(defn run! [{:keys [profile] :or {profile :small}}]
  (let [profile (keyword profile)
        counts (get profile->counts profile)]
    (when-not counts
      (throw (ex-info "profile inválido, use :small, :medium ou :large" {:profile profile})))
    (let [{:keys [users movies relations-per-user]} counts
          rng (java.util.Random. (hash profile))
          config (config/load-config)
          spicedb (component/start (spicedb-client/new-spicedb-client (:spicedb config)))
          ds-component (component/start (datasource/new-datasource-component (:db-app config)))
          ds (:datasource ds-component)
          gen-users-data (gen-users users)
          gen-movies-data (gen-movies movies)
          relations (gen-relations rng gen-users-data gen-movies-data relations-per-user)]
      (log/info "Seeding profile" profile "-" users "users," movies "movies," (count relations) "relações")
      (doseq [user gen-users-data] (users-repo/upsert! ds user))
      (doseq [movie gen-movies-data] (movies-repo/upsert! ds movie))
      (authz/write-relationships! spicedb relations)
      (log/info "Seed volumétrica concluída para profile" profile)
      (component/stop spicedb)
      (component/stop ds-component)
      {:profile profile :users users :movies movies :relations (count relations)})))
