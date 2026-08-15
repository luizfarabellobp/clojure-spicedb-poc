(ns streaming-authz.infra.spicedb.client
  (:require [com.stuartsierra.component :as component]
            [streaming-authz.domain.authz-client :as authz]
            [clojure.tools.logging :as log])
  (:import [io.grpc ManagedChannel ManagedChannelBuilder]
           [com.authzed.grpcutil BearerToken]
           [com.authzed.api.v1
            PermissionsServiceGrpc
            SchemaServiceGrpc
            CheckPermissionRequest
            CheckPermissionResponse
            CheckPermissionResponse$Permissionship
            LookupResourcesRequest
            LookupResourcesResponse
            WriteSchemaRequest
            WriteRelationshipsRequest
            RelationshipUpdate
            RelationshipUpdate$Operation
            Relationship
            ObjectReference
            SubjectReference
            Consistency]))

(defn- object-ref ^ObjectReference [type id]
  (-> (ObjectReference/newBuilder)
      (.setObjectType type)
      (.setObjectId id)
      (.build)))

(defn- subject-ref ^SubjectReference [type id]
  (-> (SubjectReference/newBuilder)
      (.setObject (object-ref type id))
      (.build)))

(defn- fully-consistent ^Consistency []
  (-> (Consistency/newBuilder)
      (.setFullyConsistent true)
      (.build)))

(defrecord SpiceDBClient [endpoint preshared-key channel permissions-stub schema-stub]
  component/Lifecycle
  (start [this]
    (log/info "Connecting to SpiceDB at" endpoint)
    (let [ch (-> (ManagedChannelBuilder/forTarget endpoint)
                 (.usePlaintext)
                 (.build))
          creds (BearerToken. preshared-key)]
      (assoc this
             :channel ch
             :permissions-stub (-> (PermissionsServiceGrpc/newBlockingStub ch)
                                    (.withCallCredentials creds))
             :schema-stub (-> (SchemaServiceGrpc/newBlockingStub ch)
                               (.withCallCredentials creds)))))
  (stop [this]
    (when channel
      (log/info "Closing SpiceDB channel")
      (.shutdownNow ^ManagedChannel channel))
    (assoc this :channel nil :permissions-stub nil :schema-stub nil))

  authz/AuthzClient
  (write-schema! [_ schema-str]
    (.writeSchema schema-stub
                  (-> (WriteSchemaRequest/newBuilder)
                      (.setSchema schema-str)
                      (.build))))

  (write-relationships! [_ tuples]
    (let [builder (WriteRelationshipsRequest/newBuilder)]
      (doseq [{:keys [resource-type resource-id relation subject-type subject-id]} tuples]
        (.addUpdates builder
          (-> (RelationshipUpdate/newBuilder)
              (.setOperation RelationshipUpdate$Operation/OPERATION_TOUCH)
              (.setRelationship
                (-> (Relationship/newBuilder)
                    (.setResource (object-ref resource-type resource-id))
                    (.setRelation relation)
                    (.setSubject (subject-ref subject-type subject-id))
                    (.build)))
              (.build))))
      (.writeRelationships permissions-stub (.build builder))))

  (check-permission [_ {:keys [resource-type resource-id permission subject-id]}]
    (let [response (.checkPermission permissions-stub
                     (-> (CheckPermissionRequest/newBuilder)
                         (.setResource (object-ref resource-type resource-id))
                         (.setPermission permission)
                         (.setSubject (subject-ref "user" subject-id))
                         (.setConsistency (fully-consistent))
                         (.build)))]
      (= (.getPermissionship response)
         CheckPermissionResponse$Permissionship/PERMISSIONSHIP_HAS_PERMISSION)))

  (lookup-resources [_ {:keys [resource-type permission subject-id]}]
    (let [it (.lookupResources permissions-stub
               (-> (LookupResourcesRequest/newBuilder)
                   (.setResourceObjectType resource-type)
                   (.setPermission permission)
                   (.setSubject (subject-ref "user" subject-id))
                   (.setConsistency (fully-consistent))
                   (.build)))]
      (mapv (fn [^LookupResourcesResponse r] (.getResourceObjectId r))
            (iterator-seq it)))))

(defn new-spicedb-client [{:keys [endpoint preshared-key]}]
  (map->SpiceDBClient {:endpoint endpoint :preshared-key preshared-key}))
