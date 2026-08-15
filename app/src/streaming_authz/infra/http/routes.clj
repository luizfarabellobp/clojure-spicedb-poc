(ns streaming-authz.infra.http.routes
  (:require [io.pedestal.http.route :as route]
            [streaming-authz.infra.http.response :as response]
            [streaming-authz.infra.http.auth-interceptor :as auth]
            [streaming-authz.domain.movie-service :as movie-service]
            [streaming-authz.domain.authz-client :as authz]))

(defn- health [_request]
  (response/ok {:status "ok"}))

(defn- movie-access [system]
  (fn [request]
    (let [movie-id (get-in request [:path-params :id])
          user-id (get-in request [:identity :user-id])
          allowed (movie-service/can-view? system {:user-id user-id :movie-id movie-id})]
      (response/ok {:allowed allowed}))))

(defn- available-movies [system]
  (fn [request]
    (let [user-id (get-in request [:identity :user-id])]
      (response/ok {:movies (movie-service/available-movies system {:user-id user-id})}))))

(defn- write-relationship [system]
  (fn [request]
    (let [{:keys [resource-type resource-id relation subject-type subject-id]} (:json-params request)]
      (authz/write-relationships! (:spicedb-client system)
        [{:resource-type resource-type :resource-id resource-id
          :relation relation :subject-type subject-type :subject-id subject-id}])
      (response/ok {:written true}))))

(defn routes [system auth-config]
  (let [auth-interceptor (auth/authenticate auth-config)]
    (route/expand-routes
      #{["/health" :get health :route-name :health]
        ["/movies/:id/access" :get [auth-interceptor (movie-access system)] :route-name :movie-access]
        ["/available-movies" :get [auth-interceptor (available-movies system)] :route-name :available-movies]
        ["/relationships" :post [auth-interceptor (write-relationship system)] :route-name :write-relationship]})))
