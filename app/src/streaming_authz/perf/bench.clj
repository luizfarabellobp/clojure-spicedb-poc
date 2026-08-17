(ns streaming-authz.perf.bench
  (:refer-clojure :exclude [run!])
  (:require [streaming-authz.config :as config]
            [streaming-authz.domain.authz-client :as authz]
            [streaming-authz.infra.spicedb.client :as spicedb-client]
            [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io])
  (:import [java.time Instant]
           [java.util.concurrent Executors Callable Future]))

(defn- percentile [sorted-ms p]
  (let [idx (int (Math/ceil (* p (dec (count sorted-ms)))))]
    (nth sorted-ms idx)))

(defn- summarize [label latencies-ms]
  (let [sorted (vec (sort latencies-ms))]
    {:label label
     :count (count sorted)
     :min-ms (first sorted)
     :p50-ms (percentile sorted 0.50)
     :p95-ms (percentile sorted 0.95)
     :p99-ms (percentile sorted 0.99)
     :max-ms (last sorted)}))

(defn- timed-ms [f]
  (let [start (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) start) 1e6)))

(defn- bench-check [spicedb resource-id subject-id n]
  (summarize (str "check-permission:" resource-id)
    (repeatedly n #(timed-ms (fn [] (authz/check-permission spicedb
                                       {:resource-type "movie" :resource-id resource-id
                                        :permission "view" :subject-id subject-id}))))))

(defn- bench-lookup [spicedb subject-id n]
  (summarize "lookup-resources"
    (repeatedly n #(timed-ms (fn [] (authz/lookup-resources spicedb
                                       {:resource-type "movie" :permission "view" :subject-id subject-id}))))))

(defn- run-concurrent-checks [spicedb resource-id subject-id total-requests concurrency]
  (let [executor (Executors/newFixedThreadPool concurrency)
        start (System/nanoTime)
        futures (mapv (fn [_]
                        (.submit executor
                                 ^Callable (fn [] (timed-ms (fn [] (authz/check-permission spicedb
                                                                      {:resource-type "movie" :resource-id resource-id
                                                                       :permission "view" :subject-id subject-id}))))))
                      (range total-requests))
        latencies (mapv #(.get ^Future %) futures)
        elapsed-s (/ (- (System/nanoTime) start) 1e9)]
    (.shutdown executor)
    (assoc (summarize (str "check-permission-concurrent:" resource-id) latencies)
           :total-requests total-requests
           :concurrency concurrency
           :elapsed-s elapsed-s
           :throughput-rps (/ total-requests elapsed-s))))

(defn run-concurrent! [{:keys [profile concurrency total-requests check-resource-id check-subject-id]
                        :or {profile :small concurrency 10 total-requests 200
                             check-resource-id "grinch" check-subject-id "alice"}}]
  (let [config (config/load-config)
        spicedb (component/start (spicedb-client/new-spicedb-client (:spicedb config)))
        results (assoc (run-concurrent-checks spicedb check-resource-id check-subject-id total-requests concurrency)
                        :profile profile :timestamp (str (Instant/now)))
        report-path (str "target/perf-report-concurrent-" (name profile) "-" (System/currentTimeMillis) ".edn")]
    (io/make-parents report-path)
    (spit report-path (with-out-str (pprint/pprint results)))
    (log/info "Relatório de performance (concorrente) salvo em" report-path)
    (component/stop spicedb)
    results))

(defn run! [{:keys [profile iterations check-resource-id multi-check-resource-id check-subject-id lookup-subject-id]
             :or {profile :small iterations 100
                  check-resource-id "grinch"
                  multi-check-resource-id "duro_de_matar"
                  check-subject-id "alice"
                  lookup-subject-id "alice"}}]
  (let [config (config/load-config)
        spicedb (component/start (spicedb-client/new-spicedb-client (:spicedb config)))
        results {:profile profile
                 :timestamp (str (Instant/now))
                 :iterations iterations
                 :simple-check (bench-check spicedb check-resource-id check-subject-id iterations)
                 :multi-path-check (bench-check spicedb multi-check-resource-id check-subject-id iterations)
                 :lookup-resources (bench-lookup spicedb lookup-subject-id iterations)}
        report-path (str "target/perf-report-" (name profile) "-" (System/currentTimeMillis) ".edn")]
    (io/make-parents report-path)
    (spit report-path (with-out-str (pprint/pprint results)))
    (log/info "Relatório de performance salvo em" report-path)
    (component/stop spicedb)
    results))
