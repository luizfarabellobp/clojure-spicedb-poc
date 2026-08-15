(ns streaming-authz.dev.token
  (:refer-clojure :exclude [run!])
  (:require [buddy.sign.jwt :as jwt]
            [streaming-authz.config :as config]))

(defn run! [{:keys [user-id] :or {user-id "alice"}}]
  (let [secret (get-in (config/load-config) [:auth :jwt-secret])
        token (jwt/sign {:sub user-id} secret {:alg :hs256})]
    (println token)
    token))
