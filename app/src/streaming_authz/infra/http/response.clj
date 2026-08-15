(ns streaming-authz.infra.http.response
  (:require [jsonista.core :as json]))

(defn json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string body)})

(defn ok [body] (json-response 200 body))
(defn unauthorized [message] (json-response 401 {:error message}))
(defn service-unavailable [message] (json-response 503 {:error message}))
