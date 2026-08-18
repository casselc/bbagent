(ns bbagent.sqlite-store-test
  (:require [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
            [bbagent.sqlite-store :as ss]
            [bbagent.store :as store]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path Paths]
           [java.util UUID]))

(def ^:private result-options
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- temp-root []
  (str (Files/createTempDirectory
        "bbagent-sqlite-store-test"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- raw-connection [store]
  (jdbc/get-connection (jdbc/get-datasource (str "jdbc:sqlite:" (:path store)))))

(defn- error-category [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo failure
         (:bbagent/error (ex-data failure)))))

(defn- large-string [seed]
  (apply str (repeat 70000 seed)))

(deftest policy-schema-and-version-test
  (let [root (temp-root)
        store (ss/sqlite-store root)]
    (testing "durability policy is applied and verified"
      (is (= {:journal-mode "wal" :synchronous 2 :foreign-keys 1 :busy-timeout 5000}
             (ss/policy store))))
    (testing "schema tables and indexes exist"
      (with-open [^java.sql.Connection connection (raw-connection store)]
        (let [tables (set (map :name
                               (jdbc/execute! connection
                                              ["SELECT name FROM sqlite_master
                                                WHERE type = 'table'"]
                                              result-options)))
              indexes (set (map :name
                                (jdbc/execute! connection
                                               ["SELECT name FROM sqlite_master
                                                 WHERE type = 'index'"]
                                               result-options)))]
          (is (contains? tables "event"))
          (is (contains? tables "object"))
          (is (contains? indexes "event_session_type_seq"))
          (is (contains? indexes "event_request")))))
    (testing "user_version is 1"
      (with-open [^java.sql.Connection connection (raw-connection store)]
        (is (= 1 (:user_version
                  (jdbc/execute-one! connection ["PRAGMA user_version"]
                                     result-options))))))
    (store/close-store! store)))

(deftest append-order-reopen-list-checkpoint-request-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        request-id (str (UUID/randomUUID))
        store (ss/sqlite-store root)
        started (store/append-event! store session-id {:event/type :session/started})
        request (store/append-event! store session-id {:event/type :repl/request
                                                       :request/id request-id})
        _ (store/append-event! store session-id {:event/type :repl/result
                                                 :request/id request-id})
        checkpoint (store/append-event! store session-id
                                        {:event/type :session/checkpoint
                                         :checkpoint/reason :model-finish})]
    (is (= [1 2 3 4] (mapv :event/seq (store/events store session-id))))
    (is (= [session-id] (store/list-sessions store)))
    (is (= (:event/id request)
            (:event/id (store/first-event store session-id :repl/request))))
    (is (= (:event/id started)
           (:event/id (store/first-event store session-id :session/started))))
    (is (nil? (store/first-event store session-id :model/request)))
    (is (= (:event/id checkpoint)
           (:event/id (store/latest-checkpoint store session-id))))
    (is (= (:event/id request)
           (:event/id (store/request-event store session-id request-id))))
    (is (nil? (store/request-event store session-id "missing")))
    (is (nil? (store/latest-checkpoint store "absent-session")))
    (is (= [3 4] (mapv :event/seq
                       (store/events-after store session-id (:event/id request)))))
    (is (= [] (store/events-after store session-id (:event/id checkpoint))))
    (is (= :journal-storage-failure
           (error-category #(store/events-after store session-id "missing"))))
    (store/close-store! store)
    (testing "reopen preserves events"
      (let [reopened (ss/sqlite-store root)]
        (is (= [1 2 3 4] (mapv :event/seq (store/events reopened session-id))))
        (is (= (:event/id checkpoint)
               (:event/id (store/latest-checkpoint reopened session-id))))
        (store/close-store! reopened)))))

(deftest cas-idempotence-and-roundtrip-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (ss/sqlite-store root)
        content "a small but important payload"
        reference (store/put-object! store session-id content)
        hex (store/blob-hex (:digest (:form reference)))]
    (is (store/blob-reference? reference))
    (is (= #{:digest :bytes :encoding} (set (keys (:form reference)))))
    (is (= :utf-8 (:encoding (:form reference))))
    (is (= content (store/get-object store session-id (:digest (:form reference)))))
    (testing "put-object! is idempotent for identical content"
      (let [again (store/put-object! store session-id content)]
        (is (= (:digest (:form reference)) (:digest (:form again))))))
    (testing "missing object fails closed"
      (is (= :session-recovery-failure
             (error-category #(store/get-object
                               store session-id
                               (str "sha256:" (apply str (repeat 64 "0"))))))))
    (testing "non-string object is rejected"
      (is (= :journal-storage-failure
             (error-category #(store/put-object! store session-id 42)))))
    (store/close-store! store)))

(deftest secrets-stripped-test
  (let [root (temp-root)
        session-id "stable-session"
        store (ss/sqlite-store root)]
    (store/append-event! store session-id {:event/type :model/request
                                           :api-key "do-not-store"
                                           :headers {:authorization "Bearer do-not-store"}
                                           :usage {:completion_tokens 12}
                                           :safe "kept"})
    (let [event (first (store/events store session-id))]
      (is (= "kept" (:safe event)))
      (is (= 12 (get-in event [:usage :completion_tokens])))
      (is (nil? (:api-key event)))
      (is (= {} (:headers event))))
    (store/close-store! store)))

(deftest duplicate-event-id-rejection-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (ss/sqlite-store root)
        event-id (str (UUID/randomUUID))]
    (store/append-event! store session-id {:event/id event-id
                                           :event/type :session/started})
    (is (= :journal-storage-failure
           (error-category #(store/append-event! store session-id
                                                 {:event/id event-id
                                                  :event/type :model/request}))))
    (is (= 1 (count (store/events store session-id))))
    (store/close-store! store)))

(deftest canonical-payload-and-checksum-corruption-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (ss/sqlite-store root)]
    (store/append-event! store session-id {:event/type :session/started})
    (with-open [^java.sql.Connection connection (raw-connection store)]
      (testing "canonical payload corruption fails recovery"
        (jdbc/execute-one! connection
                           ["UPDATE event SET payload = ? WHERE session_id = ?"
                            (.getBytes "[:string \"corrupted\"]" StandardCharsets/UTF_8)
                            session-id])
        (is (= :session-recovery-failure
               (error-category #(store/events store session-id)))))
      (testing "checksum corruption fails recovery"
        (jdbc/execute-one! connection
                           ["UPDATE event SET checksum = ? WHERE session_id = ?"
                            "sha256:deadbeef" session-id])
        (is (= :session-recovery-failure
               (error-category #(store/events store session-id))))))
    (store/close-store! store)))

(deftest missing-and-corrupt-object-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (ss/sqlite-store root)
        content (large-string "z")]
    (store/append-event! store session-id {:event/type :model/response
                                           :content content})
    (is (= content (:content (first (store/events store session-id)))))
    (let [digest (coordinates/sha-256 content)]
      (testing "missing object fails recovery"
        (with-open [^java.sql.Connection connection (raw-connection store)]
          (jdbc/execute-one! connection
                             ["DELETE FROM object WHERE digest = ?" digest]))
        (is (= :session-recovery-failure
               (error-category #(store/events store session-id)))))
      (testing "corrupt object content fails recovery"
        (with-open [^java.sql.Connection connection (raw-connection store)]
          (jdbc/execute-one! connection
                             ["INSERT INTO object (digest, bytes, encoding, media_type, content)
                               VALUES (?, ?, ?, ?, ?)"
                              digest 7 "utf-8" nil
                              (.getBytes "corrupt" StandardCharsets/UTF_8)]))
        (is (= :session-recovery-failure
               (error-category #(store/events store session-id))))))
    (store/close-store! store)))

(deftest object-and-event-rollback-on-duplicate-id-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (ss/sqlite-store root)
        event-id (str (UUID/randomUUID))
        staged (large-string "newly-staged")
        staged-digest (coordinates/sha-256 staged)]
    (store/append-event! store session-id {:event/id event-id
                                           :event/type :model/response
                                           :content (large-string "original")})
    (is (= :journal-storage-failure
           (error-category #(store/append-event! store session-id
                                                 {:event/id event-id
                                                  :event/type :model/response
                                                  :content staged}))))
    (testing "the newly staged object was rolled back"
      (with-open [^java.sql.Connection connection (raw-connection store)]
        (is (nil? (jdbc/execute-one! connection
                                     ["SELECT digest FROM object WHERE digest = ?"
                                      staged-digest]
                                     result-options)))))
    (testing "prior event and object remain usable"
      (is (= 1 (count (store/events store session-id))))
      (is (= (large-string "original")
             (:content (first (store/events store session-id))))))
    (store/close-store! store)))

(deftest unsupported-newer-version-test
  (let [root (temp-root)
        store (ss/sqlite-store root)]
    (store/close-store! store)
    (with-open [^java.sql.Connection connection (raw-connection store)]
      (jdbc/execute-one! connection ["PRAGMA user_version = 2"]))
    (is (= :journal-storage-failure
           (error-category #(ss/sqlite-store root))))))

(deftest failed-migration-rollback-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (ss/sqlite-store root)]
    (store/append-event! store session-id {:event/type :session/started})
    (is (thrown? clojure.lang.ExceptionInfo
                 (ss/with-migration store
                   (fn [^java.sql.Connection connection]
                     (jdbc/execute-one! connection
                                        ["CREATE TABLE doomed (id INTEGER)"])
                     (jdbc/execute-one! connection ["PRAGMA user_version = 99"])
                     (throw (ex-info "boom" {}))))))
    (testing "prior user_version and data remain usable"
      (with-open [^java.sql.Connection connection (raw-connection store)]
        (is (= 1 (:user_version
                  (jdbc/execute-one! connection ["PRAGMA user_version"]
                                     result-options))))
        (is (empty? (jdbc/execute! connection
                                   ["SELECT name FROM sqlite_master
                                     WHERE name = 'doomed'"]
                                   result-options))))
      (is (= 1 (count (store/events store session-id)))))
    (store/close-store! store)))

(deftest close-behavior-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (ss/sqlite-store root)]
    (store/append-event! store session-id {:event/type :session/started})
    (is (nil? (store/close-store! store)))
    (is (nil? (store/close-store! store)))
    (testing "operations fail after close"
      (is (= :journal-storage-failure
             (error-category #(store/events store session-id))))
      (is (= :journal-storage-failure
             (error-category #(store/append-event! store session-id
                                                   {:event/type :session/started}))))
      (is (= :journal-storage-failure
             (error-category #(store/list-sessions store))))
      (is (= :journal-storage-failure
             (error-category #(store/put-object! store session-id "x"))))
      (is (= :journal-storage-failure
              (error-category #(store/get-object store session-id
                                                 (str "sha256:" (apply str (repeat 64 "0"))))))))))

(deftest corrupt-database-open-is-categorized-test
  (let [root (temp-root)
        store (ss/sqlite-store root)
        path (:path store)]
    (store/close-store! store)
    (Files/writeString (Paths/get path (make-array String 0)) "not a database"
                       (make-array java.nio.file.OpenOption 0))
    (is (= :journal-storage-failure
           (error-category #(ss/sqlite-store root))))))

(deftest duplicate-sequence-and-corrupt-object-conflict-test
  (let [root (temp-root)
        session-id "constraint-session"
        store (ss/sqlite-store root)
        event (store/append-event! store session-id
                                   {:event/type :session/started})
        content "verified object"
        reference (store/put-object! store session-id content)
        digest (store/blob-hex (:digest (:form reference)))]
    (with-open [^java.sql.Connection connection (raw-connection store)]
      (is (thrown? java.sql.SQLException
                   (jdbc/execute-one!
                    connection
                    ["INSERT INTO event
                      (session_id, seq, event_id, event_type, event_time,
                       payload, checksum) VALUES (?, ?, ?, ?, ?, ?, ?)"
                     session-id 1 "different-id" "session/started"
                     (:event/time event)
                     (.getBytes (store/encode-payload event)
                                StandardCharsets/UTF_8)
                     (store/semantic-checksum event)])))
      (jdbc/execute-one! connection
                         ["UPDATE object SET content = ? WHERE digest = ?"
                          (.getBytes "corrupt" StandardCharsets/UTF_8) digest]))
    (is (= :journal-storage-failure
           (error-category #(store/put-object! store session-id content))))
    (is (= 1 (store/validate-session! store session-id)))
    (store/close-store! store)))
