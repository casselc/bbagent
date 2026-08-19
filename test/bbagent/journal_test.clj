(ns bbagent.journal-test
  (:require [bbagent.journal :as journal]
            [bbagent.coordinates :as coordinates]
            [bbagent.store :as store]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption StandardOpenOption]
           [java.util UUID]))

(defn- temp-root []
  (str (Files/createTempDirectory
        "bbagent-journal-test"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- error-category [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo failure
      (:bbagent/error (ex-data failure)))))

(deftest session-path-containment-test
  (let [root (temp-root)]
    (doseq [session-id ["." ".." "../foo" "foo/../bar"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (journal/session-path root session-id))))
    (doseq [session-id ["valid-session"
                        "123e4567-e89b-12d3-a456-426614174000"]]
      (is (= session-id
             (str (.getFileName (journal/session-path root session-id))))))))

(deftest ordered-append-and-correlation-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        request-id (str (UUID/randomUUID))]
    (journal/append! store {:event/type :model/request :request/id request-id})
    (journal/append! store {:event/type :model/response :request/id request-id})
    (let [events (journal/read-events root session-id)]
      (is (= [1 2] (mapv :event/seq events)))
      (is (= [request-id request-id] (mapv :request/id events))))))

(deftest truncated-tail-recovery-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        _ (journal/append! store {:event/type :session/started})
        path (:path store)]
    (Files/write path
                 (.getBytes "{:journal/version 1" StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [reopened (journal/open! root session-id)]
      (is (= 1 (count (journal/events reopened))))
      (is (= :session/started (:event/type (first (journal/events reopened))))))))

(deftest complete-corruption-is-not-tail-recovery-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        _ (journal/append! store {:event/type :session/started})]
    (Files/write (:path store)
                 (.getBytes "{:malformed true}\n" StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [failure (try (journal/open! root session-id)
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :session-recovery-failure
             (:bbagent/error (ex-data failure)))))))

(deftest large-value-blob-round-trip-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        content (apply str (repeat 70000 "x"))]
    (journal/append! store {:event/type :model/response :content content})
    (is (= content (:content (first (journal/read-events root session-id)))))
    (is (not (.contains (slurp (str (:path store))) content)))))

(deftest blob-marker-does-not-collide-with-map-data-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        ordinary-map {:blob/digest "sha256:not-a-reference"
                      :blob/bytes 7
                      :blob/encoding :utf-8}]
    (journal/append! store {:event/type :model/response :content ordinary-map})
    (is (= ordinary-map
           (:content (first (journal/read-events root session-id)))))))

(deftest partial-existing-blob-is-replaced-atomically-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        content (apply str (repeat 70000 "z"))
        digest (coordinates/sha-256 content)
        blob-path (.resolve (:blobs store) digest)]
    (Files/write blob-path (.getBytes "partial" StandardCharsets/UTF_8)
                 (make-array OpenOption 0))
    (journal/append! store {:event/type :model/response :content content})
    (is (= content (:content (first (journal/read-events root session-id)))))))

(deftest session-id-and-secret-test
  (let [root (temp-root)
        session-id "stable-session"
        store (journal/open! root session-id)]
    (journal/append! store {:event/type :model/request
                            :api-key "do-not-store"
                            :headers {:authorization "Bearer do-not-store"}
                            :usage {:completion_tokens 12}
                            :safe "kept"})
    (is (= [session-id] (journal/list-sessions root)))
    (let [event (first (journal/read-events root session-id))]
      (is (= "kept" (:safe event)))
      (is (= 12 (get-in event [:usage :completion_tokens])))
      (is (nil? (:api-key event)))
      (is (= {} (:headers event)))
      (is (not (.contains (slurp (str (:path store))) "do-not-store"))))))

(deftest file-store-protocol-append-and-read-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        fs (journal/file-store root)
        large (apply str (repeat 70000 "b"))]
    (store/append-event! fs session-id {:event/type :session/started})
    (store/append-event! fs session-id {:event/type :session/checkpoint
                                        :checkpoint/reason :session-start})
    (store/append-event! fs session-id {:event/type :model/response
                                        :content large})
    (let [scanned (store/events fs session-id)]
      (is (= [1 2 3] (mapv :event/seq scanned)))
      (is (= large (:content (nth scanned 2))))
      (is (= scanned (journal/read-events root session-id))))
    (is (= [session-id] (store/list-sessions fs)))
    (is (nil? (store/close-store! fs)))
    (is (nil? (store/close-store! fs)))
    (is (= :journal-storage-failure
           (error-category #(store/events fs session-id))))))

(deftest corrupt-session-is-isolated-from-open-list-and-healthy-read-test
  (let [root (temp-root)
        healthy (journal/open! root "healthy")
        corrupt (journal/open! root "corrupt")]
    (journal/append! healthy {:event/type :session/started})
    (journal/append! corrupt {:event/type :session/started})
    (Files/write (:path corrupt)
                 (.getBytes "{:malformed true}\n" StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [fs (journal/file-store root)]
      (try
        (is (= ["corrupt" "healthy"] (store/list-sessions fs)))
        (is (= [:session/started]
               (mapv :event/type (store/events fs "healthy"))))
        (is (= :session-recovery-failure
               (error-category #(store/events fs "corrupt"))))
        (is (= 1 (store/validate-session! fs "healthy")))
        (finally
          (store/close-store! fs))))))

(deftest file-store-reads-use-recovered-session-cache-test
  (let [root (temp-root)
        session-id "cached-session"
        handle (journal/open! root session-id)
        original-recover journal/recover
        recoveries (atom 0)]
    (journal/append! handle {:event/type :session/started})
    (with-redefs [journal/recover (fn [path]
                                   (swap! recoveries inc)
                                   (original-recover path))]
      (let [fs (journal/file-store root)]
        (try
          (is (= 0 @recoveries))
          (is (= 1 (count (store/events fs session-id))))
          (is (= 1 (store/validate-session! fs session-id)))
          (is (= :session/started
                 (:event/type (store/first-event fs session-id
                                                 :session/started))))
          (is (= [] (store/unresolved-effects fs session-id)))
          (is (= 1 @recoveries))
          (finally
            (store/close-store! fs)))))))

(deftest corrupt-root-audit-blocks-appends-but-not-reads-test
  (let [root (temp-root)
        healthy (journal/open! root "healthy")
        corrupt (journal/open! root "corrupt")]
    (journal/append! healthy {:event/type :session/started})
    (journal/append! corrupt {:event/type :session/started})
    (Files/write (:path corrupt)
                 (.getBytes "{:malformed true}\n" StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [fs (journal/file-store root)]
      (try
        (is (= 1 (count (store/events fs "healthy"))))
        (is (= :journal-storage-failure
               (error-category
                #(store/append-event! fs "healthy"
                                      {:event/type :user/message}))))
        (is (= 1 (count (store/events fs "healthy"))))
        (is (= ["corrupt" "healthy"] (store/list-sessions fs)))
        (finally
          (store/close-store! fs))))))

(deftest root-audit-rejects-cross-session-event-id-duplicates-test
  (let [root (temp-root)
        duplicate-id "duplicate-across-sessions"
        first-handle (journal/open! root "first")
        second-handle (journal/open! root "second")]
    (journal/append! first-handle {:event/id duplicate-id
                                   :event/type :session/started})
    (journal/append! second-handle {:event/id duplicate-id
                                    :event/type :session/started})
    (let [fs (journal/file-store root)]
      (try
        (is (= 1 (count (store/events fs "first"))))
        (is (= 1 (count (store/events fs "second"))))
        (is (= :journal-storage-failure
               (error-category
                #(store/append-event! fs "first"
                                      {:event/type :user/message}))))
        (finally
          (store/close-store! fs))))))

(deftest file-store-exclusive-lock-close-and-reopen-test
  (let [root (temp-root)
        fs (journal/file-store root)]
    (is (= :journal-storage-failure
           (error-category #(journal/file-store root))))
    (is (nil? (store/close-store! fs)))
    (is (nil? (store/close-store! fs)))
    (doseq [operation [#(store/append-event! fs "closed" {:event/type :test})
                       #(store/events fs "closed")
                       #(store/validate-session! fs "closed")
                       #(store/unresolved-effects fs "closed")
                       #(store/events-after fs "closed" "event")
                       #(store/first-event fs "closed" :test)
                       #(store/latest-checkpoint fs "closed")
                       #(store/request-event fs "closed" "request")
                       #(store/list-sessions fs)
                       #(store/put-object! fs "closed" "value")
                       #(store/get-object fs "closed" (str "sha256:"
                                                          (apply str
                                                                 (repeat 64 "0"))))]]
      (is (= :journal-storage-failure (error-category operation))))
    (let [reopened (journal/file-store root)]
      (try
        (is (= [] (store/list-sessions reopened)))
        (finally
          (store/close-store! reopened))))))

(deftest list-sessions-is-metadata-only-and-excludes-object-only-dirs-test
  (let [root (temp-root)
        event-handle (journal/open! root "event-session")
        _empty-handle (journal/open! root "empty-journal")
        torn-handle (journal/open! root "torn-first-record")]
    (journal/append! event-handle {:event/type :session/started})
    (Files/write (:path torn-handle)
                 (.getBytes "{:journal/version 1" StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/CREATE]))
    (let [fs (journal/file-store root)]
      (try
        (store/put-object! fs "object-only" "payload")
        (is (= ["event-session"] (store/list-sessions fs)))
        (is (= [] (store/events fs "torn-first-record")))
        (is (= ["event-session"] (store/list-sessions fs)))
        (finally
          (store/close-store! fs))))))

(deftest duplicate-event-id-rejection-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        fs (journal/file-store root)
        event-id (str (UUID/randomUUID))]
    (store/append-event! fs session-id {:event/id event-id
                                        :event/type :session/started})
    (let [failure (try (store/append-event! fs session-id
                                            {:event/id event-id
                                             :event/type :model/request})
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :journal-storage-failure
              (:bbagent/error (ex-data failure)))))
    (is (= 1 (count (store/events fs session-id))))
    (let [handle (journal/open! root session-id)
          failure (try (journal/append! handle {:event/id event-id})
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :journal-storage-failure
             (:bbagent/error (ex-data failure)))))))

(deftest recover-rejects-duplicate-event-ids-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        handle (journal/open! root session-id)
        first-event (journal/append! handle {:event/type :session/started})
        forged (assoc first-event :event/seq 2)
        record {:journal/version 1
                :journal/event forged
                :journal/checksum
                (coordinates/digest :bbagent/journal-event forged)}]
    (Files/write (:path handle)
                 (.getBytes (str (pr-str record) "\n") StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [failure (try (journal/open! root session-id)
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :session-recovery-failure
             (:bbagent/error (ex-data failure)))))))

(deftest store-event-query-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        fs (journal/file-store root)
        request-id (str (UUID/randomUUID))
        started (store/append-event! fs session-id {:event/type :session/started})
        request (store/append-event! fs session-id {:event/type :repl/request
                                                     :request/id request-id})
        _ (store/append-event! fs session-id {:event/type :repl/result
                                               :request/id request-id})
        checkpoint (store/append-event! fs session-id
                                        {:event/type :session/checkpoint
                                         :checkpoint/reason :model-finish})]
    (is (= [3 4]
           (mapv :event/seq
                 (store/events-after fs session-id (:event/id request)))))
    (is (= [] (store/events-after fs session-id (:event/id checkpoint))))
    (let [failure (try (store/events-after fs session-id "missing")
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :journal-storage-failure
              (:bbagent/error (ex-data failure)))))
    (is (= (:event/id request)
            (:event/id (store/first-event fs session-id :repl/request))))
    (is (= (:event/id started)
           (:event/id (store/first-event fs session-id :session/started))))
    (is (nil? (store/first-event fs session-id :model/request)))
    (is (= (:event/id checkpoint)
           (:event/id (store/latest-checkpoint fs session-id))))
    (is (= (:event/id request)
           (:event/id (store/request-event fs session-id request-id))))
    (is (nil? (store/request-event fs session-id "missing")))
    (is (nil? (store/latest-checkpoint fs "absent-session")))))

(deftest store-object-round-trip-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        fs (journal/file-store root)
        content "a small but important payload"
        reference (store/put-object! fs session-id content)
        hex (store/blob-hex (:digest (:form reference)))]
    (is (store/blob-reference? reference))
    (is (= #{:digest :bytes :encoding} (set (keys (:form reference)))))
    (is (= :utf-8 (:encoding (:form reference))))
    (is (Files/isRegularFile
         (.resolve (.resolve (journal/session-path root session-id) "blobs")
                   hex)
         (make-array java.nio.file.LinkOption 0)))
    (is (= content (store/get-object fs session-id (:digest (:form reference)))))
    (let [failure (try (store/get-object fs session-id
                                         (str "sha256:" (apply str (repeat 64 "0"))))
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :session-recovery-failure
             (:bbagent/error (ex-data failure)))))
    (let [failure (try (store/put-object! fs session-id 42)
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :journal-storage-failure
             (:bbagent/error (ex-data failure)))))
    (is (= [] (store/list-sessions fs)))))

(deftest canonical-payload-round-trip-test
  (let [value {:nil nil
               :boolean true
               :string "text"
               :character \x
               :keyword :namespaced/key
               :symbol 'namespaced/sym
               :long 42
               :big-integer 123456789012345678901234567890123456789N
               :vector [1 2 3]
               :list (list 1 2 3)
               :set #{1 2 3}
               :tagged (tagged-literal 'bbagent/blob
                                       {:digest "sha256:abc"
                                        :bytes 3
                                        :encoding :utf-8})}
        encoded (store/encode-payload value)]
    (is (= encoded (coordinates/canonical-string value)))
    (is (= encoded (store/encode-payload (into (array-map) (reverse value)))))
    (is (= value (store/decode-payload encoded))))
  (is (thrown? clojure.lang.ExceptionInfo
               (store/decode-payload "[:unknown 1]"))))

(deftest sha-256-bytes-matches-string-test
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (coordinates/sha-256-bytes (.getBytes "" StandardCharsets/UTF_8))))
  (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
         (coordinates/sha-256 "hello")))
  (is (= (coordinates/sha-256 "hello world")
          (coordinates/sha-256-bytes
           (.getBytes "hello world" StandardCharsets/UTF_8)))))

(deftest foreign-tagged-literal-round-trip-test
  (let [root (temp-root)
        session-id "foreign-tag-session"
        fs (journal/file-store root)
        value (tagged-literal 'example/value {:x 1})]
    (store/append-event! fs session-id
                         {:event/type :model/response :content value})
    (is (= value (:content (first (store/events fs session-id)))))))
