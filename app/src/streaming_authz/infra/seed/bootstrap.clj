(ns streaming-authz.infra.seed.bootstrap
  (:require [streaming-authz.domain.authz-client :as authz]
            [streaming-authz.infra.db.movies-repo :as movies-repo]
            [streaming-authz.infra.db.users-repo :as users-repo]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(def seed-tuples
  [{:resource-type "plan" :resource-id "basic" :relation "inherits" :subject-type "plan" :subject-id "medium"}
   {:resource-type "plan" :resource-id "medium" :relation "inherits" :subject-type "plan" :subject-id "premium"}

   {:resource-type "movie" :resource-id "grinch" :relation "required_plan" :subject-type "plan" :subject-id "basic"}
   {:resource-type "movie" :resource-id "grinch" :relation "tag" :subject-type "content_tag" :subject-id "natalinos"}

   {:resource-type "movie" :resource-id "duro_de_matar" :relation "required_plan" :subject-type "plan" :subject-id "premium"}
   {:resource-type "movie" :resource-id "duro_de_matar" :relation "tag" :subject-type "content_tag" :subject-id "natalinos"}

   {:resource-type "movie" :resource-id "avatar_3" :relation "required_plan" :subject-type "plan" :subject-id "premium"}
   {:resource-type "movie" :resource-id "avatar_3" :relation "tag" :subject-type "content_tag" :subject-id "blockbuster"}

   {:resource-type "content_tag" :resource-id "natalinos" :relation "allowed_product" :subject-type "commercial_product" :subject-id "promo_natal"}

   {:resource-type "plan" :resource-id "basic" :relation "subscriber" :subject-type "user" :subject-id "alice"}
   {:resource-type "commercial_product" :resource-id "promo_natal" :relation "buyer" :subject-type "user" :subject-id "alice"}

   {:resource-type "plan" :resource-id "medium" :relation "subscriber" :subject-type "user" :subject-id "bob"}
   {:resource-type "movie" :resource-id "avatar_3" :relation "direct_viewer" :subject-type "user" :subject-id "bob"}])

(def seed-movies
  [{:id "grinch" :title "O Grinch" :synopsis "Um personagem rabugento tenta arruinar o Natal."}
   {:id "duro_de_matar" :title "Duro de Matar" :synopsis "Um policial enfrenta terroristas em um arranha-céu."}
   {:id "avatar_3" :title "Avatar 3" :synopsis "Uma nova aventura em Pandora."}])

(def seed-users
  [{:id "alice" :email "alice@example.com" :display-name "Alice"}
   {:id "bob" :email "bob@example.com" :display-name "Bob"}])

(defn seed! [spicedb-client datasource]
  (log/info "Applying SpiceDB schema")
  (authz/write-schema! spicedb-client (slurp (io/resource "schema.zed")))
  (log/info "Writing seed relationships")
  (authz/write-relationships! spicedb-client seed-tuples)
  (log/info "Upserting seed movies/users into app database")
  (doseq [movie seed-movies] (movies-repo/upsert! datasource movie))
  (doseq [user seed-users] (users-repo/upsert! datasource user))
  (log/info "Seed fixa concluída"))
