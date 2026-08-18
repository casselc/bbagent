(ns bbagent.sqlite-store
  "S0b SQLite-backed durable event and object store.

  Implements the backend-neutral bbagent.store protocols over one managed
  java.sql.Connection to a single database at STATE_ROOT/bbagent.sqlite3.
  The schema is versioned through PRAGMA user_version and created
  transactionally.  All event/object semantics (secret stripping, event
  preparation, large-string externalization, hydration, semantic checksums,
  canonical payload encoding) are delegated to bbagent.store so the SQLite
   backend reproduces the A0 journal's semantic event contract."
  (:require [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
            [bbagent.store :as store]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption Path Paths]
           [java.sql Connection]))

(def ^:private result-options
  {:builder-fn rs/as-unqualified-lower-maps})

(def ^:private schema-version 1)

(def ^:private schema-statements
  ["CREATE TABLE event (session_id TEXT NOT NULL, seq INTEGER NOT NULL CHECK (seq > 0), event_id TEXT NOT NULL UNIQUE, event_type TEXT NOT NULL, event_time TEXT NOT NULL, request_id TEXT, action_id TEXT, payload BLOB NOT NULL, checksum TEXT NOT NULL, PRIMARY KEY (session_id, seq)) STRICT"
   "CREATE TABLE object (digest TEXT PRIMARY KEY, bytes INTEGER NOT NULL CHECK (bytes >= 0), encoding TEXT NOT NULL, media_type TEXT, content BLOB NOT NULL) STRICT"
   "CREATE INDEX event_session_type_seq ON event (session_id, event_type, seq DESC)"
   "CREATE INDEX event_request ON event (session_id, request_id) WHERE request_id IS NOT NULL"])

(def ^:private event-columns
  "session_id, seq, event_id, event_type, event_time, request_id, action_id, payload, checksum")

(defrecord SqliteStore [^Connection connection lock closed? policy path])

(defn- keyword-string [k]
  (when (keyword? k) (subs (str k) 1)))

(defn- ensure-open! [store]
  (when @(:closed? store)
    (throw (errors/error :journal-storage-failure "SQLite store is closed" {}))))

(defn- recovery-failure [failure]
  (errors/error :session-recovery-failure
                "SQLite session integrity check failed" {} failure))

(defn- run-transaction!
  "Runs f (a function of the java.sql.Connection) inside an explicit
   transaction.  On failure rolls back and rethrows, leaving prior
   user_version and data usable."
  [^Connection connection f]
  (let [auto (.getAutoCommit connection)]
    (try
      (.setAutoCommit connection false)
      (let [result (f connection)]
        (.commit connection)
        result)
      (catch Throwable failure
        (.rollback connection)
        (throw failure))
      (finally
        (.setAutoCommit connection auto)))))

(defn with-migration
  "Runs migration-fn (a function of the java.sql.Connection) inside an
   explicit transaction on the store's connection.  On failure the
   transaction is rolled back and the failure is rethrown, leaving prior
   user_version and data usable.  Intended for tests."
  [store migration-fn]
  (locking (:lock store)
    (ensure-open! store)
    (run-transaction! (:connection store) migration-fn)))

(defn- create-schema! [^Connection connection]
  (doseq [statement schema-statements]
    (jdbc/execute-one! connection [statement]))
  (jdbc/execute-one! connection [(str "PRAGMA user_version = " schema-version)]))

(defn- apply-pragmas! [^Connection connection]
  (jdbc/execute-one! connection ["PRAGMA journal_mode = WAL"])
  (jdbc/execute-one! connection ["PRAGMA synchronous = FULL"])
  (jdbc/execute-one! connection ["PRAGMA foreign_keys = ON"])
  (jdbc/execute-one! connection ["PRAGMA busy_timeout = 5000"])
  (let [policy {:journal-mode (:journal_mode
                               (jdbc/execute-one! connection ["PRAGMA journal_mode"]
                                                  result-options))
                :synchronous (:synchronous
                              (jdbc/execute-one! connection ["PRAGMA synchronous"]
                                                 result-options))
                :foreign-keys (:foreign_keys
                               (jdbc/execute-one! connection ["PRAGMA foreign_keys"]
                                                  result-options))
                :busy-timeout (:timeout
                               (jdbc/execute-one! connection ["PRAGMA busy_timeout"]
                                                  result-options))}]
    (when-not (and (= "wal" (:journal-mode policy))
                   (= 2 (:synchronous policy))
                   (= 1 (:foreign-keys policy))
                   (= 5000 (:busy-timeout policy)))
      (throw (errors/error :journal-storage-failure
                           "SQLite durability policy not applied" {:policy policy})))
    policy))

(defn- state-root-path [state-root]
  (let [^Path input (Paths/get (str state-root) (make-array String 0))
        ^Path absolute (.toAbsolutePath input)
        ^Path normalized (.normalize absolute)]
    (Files/createDirectories normalized
                             (make-array java.nio.file.attribute.FileAttribute 0))
    normalized))

(defn sqlite-store
  "Opens the root-level SQLite store at STATE_ROOT/bbagent.sqlite3, creating
   the state root directories and the fresh schema when needed.  Applies and
   verifies the conservative SQLite durability policy."
  [state-root]
  (let [^Path root (state-root-path state-root)
        ^Path db-path (.resolve root "bbagent.sqlite3")
        _ (Class/forName "org.sqlite.JDBC")
        datasource (jdbc/get-datasource (str "jdbc:sqlite:" db-path))
        ^Connection connection (jdbc/get-connection datasource)
        lock (Object.)
        closed? (atom false)]
    (try
      (let [policy (apply-pragmas! connection)
            version (:user_version
                     (jdbc/execute-one! connection ["PRAGMA user_version"]
                                        result-options))]
        (cond
          (= 0 version) (run-transaction! connection create-schema!)
          (> version schema-version)
          (throw (errors/error :journal-storage-failure
                               "Unsupported SQLite schema version"
                               {:user_version version}))
          :else nil)
        (->SqliteStore connection lock closed? policy (str db-path)))
      (catch Throwable failure
        (.close connection)
        (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
          (throw failure)
          (throw (errors/error :journal-storage-failure
                               "Could not open SQLite store"
                               {:database (str db-path)} failure)))))))

(defn policy
  "Returns the applied and verified SQLite durability policy map."
  [store]
  (:policy store))

(defn- object-storer [^Connection connection]
  (fn [digest ^String value]
    (let [bytes (.getBytes value StandardCharsets/UTF_8)
          existing (jdbc/execute-one!
                    connection
                    ["SELECT digest, bytes, encoding, media_type, content
                      FROM object WHERE digest = ?" digest]
                    result-options)]
      (if existing
        (let [content (String. ^bytes (:content existing) StandardCharsets/UTF_8)]
          (when-not (and (= digest (:digest existing))
                         (= (alength bytes) (:bytes existing))
                         (= "utf-8" (:encoding existing))
                         (nil? (:media_type existing))
                         (= value content))
            (throw (errors/error :journal-storage-failure
                                 "Corrupt object conflict"
                                 {:blob/digest digest}))))
        (jdbc/execute-one!
         connection
         ["INSERT INTO object (digest, bytes, encoding, media_type, content)
           VALUES (?, ?, ?, ?, ?)"
          digest (alength bytes) "utf-8" nil bytes])))))

(defn- object-loader [^Connection connection]
  (fn [digest]
    (let [row (jdbc/execute-one!
               connection
               ["SELECT digest, bytes, encoding, media_type, content
                 FROM object WHERE digest = ?" digest]
               result-options)]
      (when row
        (let [content (String. ^bytes (:content row) StandardCharsets/UTF_8)]
          (when-not (and (= digest (:digest row))
                         (= (alength (.getBytes content StandardCharsets/UTF_8))
                            (:bytes row))
                         (= "utf-8" (:encoding row))
                         (nil? (:media_type row)))
            (throw (ex-info "Object row corrupt" {:blob/digest digest})))
          content)))))

(defn- row->event [^Connection connection session-id row]
  (let [payload (String. ^bytes (:payload row) StandardCharsets/UTF_8)
        event (store/decode-payload payload)
        event-type (:event/type event)]
    (when-not (= session-id (:session_id row))
      (throw (ex-info "Event session id mismatch" {})))
    (when-not (and (keyword? event-type)
                   (= (:event_type row) (keyword-string event-type)))
      (throw (ex-info "Event type mismatch" {})))
    (when-not (= (:checksum row) (store/semantic-checksum event))
      (throw (ex-info "Event checksum mismatch" {})))
    (when-not (= (:seq row) (:event/seq event))
      (throw (ex-info "Event seq mismatch" {})))
    (when-not (= (:event_id row) (:event/id event))
      (throw (ex-info "Event id mismatch" {})))
    (when-not (= (:event_time row) (:event/time event))
      (throw (ex-info "Event time mismatch" {})))
    (when-not (= (:request_id row) (:request/id event))
      (throw (ex-info "Event request id mismatch" {})))
    (when-not (= (:action_id row) (:action/id event))
      (throw (ex-info "Event action id mismatch" {})))
    (store/hydrate (object-loader connection) event)))

(defn- validate-contiguous [^long start events]
  (let [expected (mapv #(+ start %) (range (count events)))]
    (when-not (= expected (mapv :event/seq events))
      (throw (ex-info "Event sequence is discontinuous" {})))
    (let [ids (keep :event/id events)]
      (when-not (= (count ids) (count (set ids)))
        (throw (ex-info "Duplicate event ID" {}))))))

(defn- duplicate-event-id? [failure]
  (and (instance? java.sql.SQLException failure)
       (boolean (re-find #"UNIQUE constraint failed"
                         (.getMessage ^java.sql.SQLException failure)))))

(defn- classify-write [failure]
  (cond
    (= :journal-storage-failure (:bbagent/error (ex-data failure))) failure
    (duplicate-event-id? failure)
    (errors/error :journal-storage-failure "Duplicate event ID" {} failure)
    :else
    (errors/error :journal-storage-failure "SQLite append failed" {} failure)))

(defn append-event! [store session-id event]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (let [^Connection connection (:connection store)]
      (try
        (jdbc/execute-one! connection ["BEGIN IMMEDIATE"])
        (let [max-row (jdbc/execute-one!
                       connection
                       ["SELECT COALESCE(MAX(seq), 0) AS max_seq
                         FROM event WHERE session_id = ?" session-id]
                       result-options)
              seq-number (inc (:max_seq max-row))
              prepared (store/prepare-event (store/strip-secrets event)
                                            seq-number)
              stored-event (store/externalize (object-storer connection)
                                              prepared)
              payload (store/encode-payload stored-event)
              checksum (store/semantic-checksum stored-event)]
          (jdbc/execute-one!
           connection
           ["INSERT INTO event (session_id, seq, event_id, event_type,
                                event_time, request_id, action_id,
                                payload, checksum)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            session-id seq-number (:event/id stored-event)
             (keyword-string (:event/type stored-event))
            (:event/time stored-event)
            (:request/id stored-event)
            (:action/id stored-event)
            (.getBytes payload StandardCharsets/UTF_8)
            checksum])
          (jdbc/execute-one! connection ["COMMIT"])
          prepared)
        (catch Throwable failure
          (try (jdbc/execute-one! connection ["ROLLBACK"])
               (catch Throwable _ nil))
          (throw (classify-write failure)))))))

(defn events [store session-id]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (let [^Connection connection (:connection store)]
      (try
        (let [rows (jdbc/execute!
                    connection
                    [(str "SELECT " event-columns
                          " FROM event WHERE session_id = ? ORDER BY seq")
                     session-id]
                    result-options)
              stored (mapv #(row->event connection session-id %) rows)]
          (validate-contiguous 1 stored)
          stored)
        (catch Throwable failure
          (throw (recovery-failure failure)))))))

(defn validate-session! [store session-id]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (let [^Connection connection (:connection store)]
      (try
        (:count
         (reduce
          (fn [{:keys [expected ids count]} row]
            (let [event (row->event connection session-id row)
                  event-id (:event/id event)]
              (when-not (= expected (:event/seq event))
                (throw (ex-info "Event sequence is discontinuous" {})))
              (when (contains? ids event-id)
                (throw (ex-info "Duplicate event ID" {})))
              {:expected (inc expected)
               :ids (conj ids event-id)
               :count (inc count)}))
          {:expected 1 :ids #{} :count 0}
          (jdbc/plan connection
                     [(str "SELECT " event-columns
                           " FROM event WHERE session_id = ? ORDER BY seq")
                      session-id]
                     result-options)))
        (catch Throwable failure
          (throw (recovery-failure failure)))))))

(defn events-after [store session-id event-id]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (let [^Connection connection (:connection store)]
    (try
      (let [cursor (jdbc/execute-one!
                    connection
                    ["SELECT seq FROM event WHERE session_id = ? AND event_id = ?"
                     session-id event-id]
                    result-options)]
        (when (nil? cursor)
          (throw (errors/error :journal-storage-failure "Unknown event ID"
                               {:event/id event-id})))
        (let [rows (jdbc/execute!
                    connection
                    [(str "SELECT " event-columns
                          " FROM event WHERE session_id = ? AND seq > ?
                            ORDER BY seq")
                     session-id (:seq cursor)]
                    result-options)
              stored (mapv #(row->event connection session-id %) rows)]
          (validate-contiguous (inc (:seq cursor)) stored)
          stored))
      (catch clojure.lang.ExceptionInfo failure
        (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
          (throw failure)
          (throw (recovery-failure failure))))
      (catch Throwable failure
        (throw (recovery-failure failure)))))))

(defn- first-row [^Connection connection sql session-id value]
  (jdbc/execute-one! connection [sql session-id value] result-options))

(defn first-event [store session-id event-type]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (let [^Connection connection (:connection store)]
    (try
      (when-let [row (first-row connection
                                (str "SELECT " event-columns
                                     " FROM event WHERE session_id = ?
                                       AND event_type = ?
                                       ORDER BY seq ASC LIMIT 1")
                                 session-id (keyword-string event-type))]
        (row->event connection session-id row))
      (catch Throwable failure
        (throw (recovery-failure failure)))))))

(defn latest-checkpoint [store session-id]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (let [^Connection connection (:connection store)]
    (try
      (when-let [row (first-row connection
                                (str "SELECT " event-columns
                                     " FROM event WHERE session_id = ?
                                       AND event_type = ?
                                       ORDER BY seq DESC LIMIT 1")
                                session-id "session/checkpoint")]
        (row->event connection session-id row))
      (catch Throwable failure
        (throw (recovery-failure failure)))))))

(defn request-event [store session-id request-id]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (let [^Connection connection (:connection store)]
    (try
      (when-let [row (first-row connection
                                 (str "SELECT " event-columns
                                      " FROM event WHERE session_id = ?
                                        AND request_id = ?
                                        AND event_type = 'repl/request'
                                        ORDER BY seq ASC LIMIT 1")
                                session-id request-id)]
        (row->event connection session-id row))
      (catch Throwable failure
        (throw (recovery-failure failure)))))))

(defn list-sessions [store]
  (locking (:lock store)
    (ensure-open! store)
    (let [^Connection connection (:connection store)]
    (try
      (mapv :session_id
            (jdbc/execute! connection
                           ["SELECT DISTINCT session_id FROM event
                             ORDER BY session_id"]
                           result-options))
      (catch Throwable failure
        (throw (recovery-failure failure)))))))

(defn put-object! [store session-id value]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (when-not (string? value)
      (throw (errors/error :journal-storage-failure
                           "Store objects must be strings")))
    (run-transaction! (:connection store)
                      (fn [^Connection connection]
                        (store/store-string (object-storer connection) value)))))

(defn get-object [store session-id digest]
  (locking (:lock store)
    (ensure-open! store)
    (store/validate-session-id! session-id)
    (let [^Connection connection (:connection store)
          hex (store/blob-hex digest)]
    (when-not (and hex (re-matches #"[0-9a-f]{64}" hex))
      (throw (errors/error :session-recovery-failure
                           "Store object is missing or malformed"
                           {:blob/digest digest})))
    (try
      (let [row (jdbc/execute-one!
                 connection
                 ["SELECT digest, bytes, encoding, media_type, content
                   FROM object WHERE digest = ?" hex]
                 result-options)]
        (when (nil? row)
          (throw (errors/error :session-recovery-failure
                               "Store object is missing or malformed"
                               {:blob/digest digest})))
        (let [content (String. ^bytes (:content row) StandardCharsets/UTF_8)]
          (when-not (and (= hex (:digest row))
                         (= (alength (.getBytes content StandardCharsets/UTF_8))
                            (:bytes row))
                         (= "utf-8" (:encoding row))
                         (nil? (:media_type row))
                         (= hex (coordinates/sha-256 content)))
            (throw (errors/error :session-recovery-failure
                                 "Store object integrity check failed"
                                 {:blob/digest digest})))
          content))
      (catch clojure.lang.ExceptionInfo failure
        (if (= :session-recovery-failure (:bbagent/error (ex-data failure)))
          (throw failure)
          (throw (recovery-failure failure))))
      (catch Throwable failure
        (throw (recovery-failure failure)))))))

(defn close-store! [store]
  (locking (:lock store)
    (when-not @(:closed? store)
      (.close ^Connection (:connection store))
      (reset! (:closed? store) true)))
  nil)

(extend-protocol store/EventStore
  SqliteStore
  (append-event! [store session-id event]
    (append-event! store session-id event))
  (events [store session-id]
    (events store session-id))
  (validate-session! [store session-id]
    (validate-session! store session-id))
  (events-after [store session-id event-id]
    (events-after store session-id event-id))
  (first-event [store session-id event-type]
    (first-event store session-id event-type))
  (latest-checkpoint [store session-id]
    (latest-checkpoint store session-id))
  (request-event [store session-id request-id]
    (request-event store session-id request-id))
  (list-sessions [store]
    (list-sessions store)))

(extend-protocol store/ObjectStore
  SqliteStore
  (put-object! [store session-id value]
    (put-object! store session-id value))
  (get-object [store session-id digest]
    (get-object store session-id digest)))

(extend-protocol store/StoreLifecycle
  SqliteStore
  (close-store! [store]
    (close-store! store)))
