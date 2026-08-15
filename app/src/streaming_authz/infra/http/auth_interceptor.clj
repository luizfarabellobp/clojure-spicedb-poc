(ns streaming-authz.infra.http.auth-interceptor
  (:require [io.pedestal.interceptor :as interceptor]
            [buddy.sign.jwt :as jwt]
            [streaming-authz.infra.http.response :as response]))

(defn- extract-token [request]
  (when-let [header (get-in request [:headers "authorization"])]
    (second (re-matches #"(?i)Bearer\s+(.+)" header))))

(defn authenticate [{:keys [jwt-secret]}]
  (interceptor/interceptor
    {:name ::authenticate
     :enter (fn [context]
              (let [token (extract-token (:request context))
                    claims (when token
                             (try (jwt/unsign token jwt-secret {:alg :hs256})
                                  (catch Exception _ nil)))]
                (if claims
                  (assoc-in context [:request :identity] {:user-id (:sub claims)})
                  (assoc context :response (response/unauthorized "missing or invalid token")))))}))
