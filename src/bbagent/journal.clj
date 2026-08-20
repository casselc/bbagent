(ns bbagent.journal
  (:require [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
            [bbagent.store :as store]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel FileLock OverlappingFileLockException]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files LinkOption OpenOption Path Paths
            StandardCopyOption StandardOpenOption]
           [java.util UUID]
           [java.util.concurrent ConcurrentHashMap]))

(defrecord Journal [^Path root ^Path path ^Path blobs state lock])

(defn- safe-session-id! [session-id]
  (store/validate-session-id! session-id))

(defn session-path [root session-id]
  (safe-session-id! session-id)
  (let [^Path root-path (Paths/get (str root) (make-array String 0))
        ^Path absolute-path (.toAbsolutePath root-path)
        ^Path absolute-root (.normalize absolute-path)
        ^Path unresolved-sessions (.resolve absolute-root "sessions")
        ^Path sessions (.normalize unresolved-sessions)
        ^Path unresolved-candidate (.resolve sessions session-id)
        ^Path candidate (.normalize unresolved-candidate)]
    (when-not (= sessions (.getParent candidate))
      (throw (errors/error :journal-storage-failure
                           "Session path escapes the sessions root")))
    candidate))

(defn- force-directory! [^Path directory]
  ;; Opening a directory as a read-only FileChannel is supported on Linux and
  ;; makes a preceding directory-entry change durable with the file contents.
  (with-open [channel (FileChannel/open
                       directory
                       (into-array OpenOption [StandardOpenOption/READ]))]
    (.force channel true)))

(defn- create-directories! [^Path directory]
  (let [missing (loop [candidate directory
                       result []]
                  (if (or (nil? candidate)
                          (Files/exists candidate (make-array LinkOption 0)))
                    result
                    (recur (.getParent candidate) (conj result candidate))))]
    (Files/createDirectories directory
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (doseq [^Path created (reverse missing)]
      (when-let [parent (.getParent created)]
        (force-directory! parent)))
    directory))

(defn- write-bytes! [^Path path bytes]
  (let [temporary (.resolveSibling
                   path (str "." (.getFileName path) "." (UUID/randomUUID) ".tmp"))]
    (try
      (with-open [channel (java.nio.channels.FileChannel/open
                           temporary
                           (into-array OpenOption
                                       [StandardOpenOption/CREATE_NEW
                                        StandardOpenOption/WRITE]))]
        (let [buffer (ByteBuffer/wrap bytes)]
          (while (.hasRemaining buffer) (.write channel buffer)))
        (.force channel true))
      (Files/move temporary path
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING]))
      (force-directory! (.getParent path))
      (finally
        (Files/deleteIfExists temporary)))))

(defn- valid-blob? [^Path path digest bytes]
  (when (Files/isRegularFile path (make-array LinkOption 0))
    (let [existing (Files/readAllBytes path)
          content (String. existing StandardCharsets/UTF_8)]
      (and (= (alength bytes) (alength existing))
           (= digest (coordinates/sha-256 content))))))

(defn- blob-storer [^Path blobs]
  (fn [digest ^String value]
    (let [path (.resolve blobs digest)
          bytes (.getBytes value StandardCharsets/UTF_8)]
      (when-not (valid-blob? path digest bytes)
        (write-bytes! path bytes)))))

(defn- blob-loader [^Path blobs]
  (fn [digest]
    (let [path (.resolve blobs digest)]
      (when (Files/isRegularFile path (make-array LinkOption 0))
        (String. (Files/readAllBytes path) StandardCharsets/UTF_8)))))

(defn- decode-line [line]
  (let [{:journal/keys [version event checksum] :as record}
        (edn/read-string {:readers {'bbagent/blob
                                    #(tagged-literal 'bbagent/blob %)}
                          :default tagged-literal}
                         line)]
    (when-not (= #{:journal/version :journal/event :journal/checksum}
                 (set (keys record)))
      (throw (ex-info "Malformed journal record" {})))
    (when-not (= 1 version)
      (throw (ex-info "Unsupported journal version" {:version version})))
    (when-not (= checksum (store/semantic-checksum event))
      (throw (ex-info "Journal checksum mismatch" {})))
    event))

(defn- scan-journal [path]
  (let [^Path path (Paths/get (str path) (make-array String 0))]
    (if-not (Files/exists path (make-array LinkOption 0))
      {:stored-events [] :valid-lines [] :tail-discarded? false}
      (let [text (slurp (str path))
            terminated? (str/ends-with? text "\n")
            parts (str/split text #"\n" -1)
            lines (vec (butlast parts))
            tail-discarded? (and (not terminated?) (boolean (seq (last parts))))]
        (try
          (loop [remaining lines
                 expected-seq 1
                 stored-events []
                 valid-lines []
                 seen-ids #{}]
            (if-let [line (first remaining)]
              (do
                (when (str/blank? line)
                  (throw (ex-info "Blank journal record" {})))
                (let [event (decode-line line)
                      event-id (:event/id event)]
                  (when-not (= expected-seq (:event/seq event))
                    (throw (ex-info "Journal event sequence is discontinuous"
                                    {:expected expected-seq
                                     :actual (:event/seq event)})))
                  (when (and (some? event-id) (contains? seen-ids event-id))
                    (throw (ex-info "Duplicate journal event ID"
                                    {:event/id event-id})))
                  (recur (subvec remaining 1) (inc expected-seq)
                         (conj stored-events event) (conj valid-lines line)
                         (if (some? event-id)
                           (conj seen-ids event-id)
                           seen-ids))))
              {:stored-events stored-events
                :valid-lines valid-lines
                :tail-discarded? tail-discarded?}))
          (catch Throwable failure
            (throw (errors/error :session-recovery-failure
                                  "Journal integrity check failed"
                                  {:path (str path)} failure))))))))

(defn recover [path]
  (let [^Path path (Paths/get (str path) (make-array String 0))
        {:keys [stored-events] :as recovery} (scan-journal path)
        load-blob (blob-loader (.resolve (.getParent path) "blobs"))]
    (try
      (-> recovery
          (assoc :events (mapv #(store/hydrate load-blob %) stored-events))
          (dissoc :stored-events))
      (catch Throwable failure
        (throw (errors/error :session-recovery-failure
                             "Journal integrity check failed"
                             {:path (str path)} failure))))))

(defn open! [root session-id]
  (try
    (let [directory (session-path root session-id)
          blobs (.resolve directory "blobs")
          path (.resolve directory "events.edn")]
      (create-directories! blobs)
      (let [{:keys [events valid-lines tail-discarded?]} (recover path)
            next-seq (or (:event/seq (peek events)) 0)]
        (when tail-discarded?
          (let [valid (if (seq valid-lines)
                        (str (str/join "\n" valid-lines) "\n")
                        "")]
            (write-bytes! path (.getBytes valid StandardCharsets/UTF_8))))
        (->Journal directory path blobs
                   (atom {:events events
                          :next-seq next-seq
                          :ids (set (keep :event/id events))})
                   (Object.))))
    (catch clojure.lang.ExceptionInfo failure
      (throw failure))
    (catch Throwable failure
      (throw (errors/error :journal-storage-failure "Could not open journal"
                           {:root (str root) :session/id session-id} failure)))))

(defn append! [^Journal journal event]
  (locking (:lock journal)
    (try
      (let [state @(:state journal)
            seq-number (inc (:next-seq state))
             event (store/prepare-event (store/strip-secrets event) seq-number)]
        (when (contains? (:ids state) (:event/id event))
          (throw (errors/error :journal-storage-failure
                               "Duplicate journal event ID"
                               {:event/id (:event/id event)})))
        (store/validate-object-references! (blob-loader (:blobs journal)) event)
        (let [logical-event (store/hydrate (blob-loader (:blobs journal)) event)
              stored-event (store/externalize (blob-storer (:blobs journal))
                                               event)
              record {:journal/version 1
                      :journal/event stored-event
                      :journal/checksum
                      (store/semantic-checksum stored-event)}
              bytes (.getBytes (str (pr-str record) "\n") StandardCharsets/UTF_8)]
          (let [new-file? (not (Files/exists (:path journal)
                                              (make-array LinkOption 0)))]
            (with-open [channel (FileChannel/open
                                 (:path journal)
                                 (into-array OpenOption
                                             [StandardOpenOption/CREATE
                                              StandardOpenOption/WRITE
                                              StandardOpenOption/APPEND]))]
              (let [buffer (ByteBuffer/wrap bytes)]
                (while (.hasRemaining buffer) (.write channel buffer)))
              (.force channel true))
            (when new-file?
              (force-directory! (.getParent (:path journal)))))
          (swap! (:state journal)
                  (fn [state] {:events (conj (:events state) logical-event)
                               :next-seq seq-number
                               :ids (conj (:ids state) (:event/id event))}))
          event))
      (catch Throwable failure
        (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
          (throw failure)
          (throw (errors/error :journal-storage-failure "Journal append failed"
                               {:path (str (:path journal))} failure)))))))

(defn events [^Journal journal]
  (:events @(:state journal)))

(defn read-events [root session-id]
  (:events (recover (.resolve (session-path root session-id) "events.edn"))))

(defn list-sessions [root]
  (let [sessions (.resolve (Paths/get (str root) (make-array String 0)) "sessions")]
    (if-not (Files/isDirectory sessions (make-array LinkOption 0))
      []
      (with-open [stream (Files/list sessions)]
        (->> (.toArray stream)
             (filter #(Files/isDirectory ^Path % (make-array LinkOption 0)))
             (filter (fn [^Path directory]
                       (let [events-path (.resolve directory "events.edn")]
                         (and (Files/isRegularFile events-path
                                                   (make-array LinkOption 0))
                              (with-open [input (Files/newInputStream
                                                events-path
                                                (make-array OpenOption 0))]
                                (loop []
                                  (let [value (.read input)]
                                    (cond
                                      (= -1 value) false
                                      (= 10 value) true
                                      :else (recur)))))))))
             (map #(str (.getFileName ^Path %)))
             sort
             vec)))))

(defn- blob-directory! [root session-id]
  (let [blobs (.resolve (session-path root session-id) "blobs")]
    (create-directories! blobs)
    blobs))

(defn- session-handle [root ^ConcurrentHashMap cache session-id]
  (let [session-id (safe-session-id! session-id)]
    (or (.get cache session-id)
        (let [journal (open! root session-id)]
          (or (.putIfAbsent cache session-id journal) journal)))))

(defn- session-directory-ids [root]
  (let [sessions (.resolve (Paths/get (str root) (make-array String 0)) "sessions")]
    (if-not (Files/isDirectory sessions (make-array LinkOption 0))
      []
      (with-open [stream (Files/list sessions)]
        (->> (.toArray stream)
             (filter #(Files/isDirectory ^Path % (make-array LinkOption 0)))
             (map #(str (.getFileName ^Path %)))
             (filter (fn [session-id]
                       (try
                         (safe-session-id! session-id)
                         true
                         (catch clojure.lang.ExceptionInfo _ false))))
             sort
             vec)))))

(defn- audit-event-ids [root]
  (reduce
   (fn [ids session-id]
     (reduce
      (fn [ids event]
        (let [event-id (:event/id event)]
          (when (and (some? event-id) (contains? ids event-id))
            (throw (errors/error :journal-storage-failure
                                 "Duplicate journal event ID across sessions"
                                 {:event/id event-id :session/id session-id})))
          (if (some? event-id) (conj ids event-id) ids)))
      ids
      (:stored-events
       (scan-journal (.resolve (session-path root session-id) "events.edn")))))
   #{}
   (list-sessions root)))

(defn- global-blob-loader [root session-id]
  (let [session-ids (cons session-id (remove #{session-id}
                                              (session-directory-ids root)))]
    (fn [digest]
      (some (fn [candidate]
              ((blob-loader (.resolve (session-path root candidate) "blobs"))
               digest))
            session-ids))))

(defn- unresolved-effects-in [events]
  (let [later-result?
        (fn [request result-type]
          (some (fn [event]
                  (and (= result-type (:event/type event))
                       (> (:event/seq event) (:event/seq request))
                       (= (:request/id request) (:request/id event))
                       (or (= :model/request (:event/type request))
                           (= (:action/id request) (:action/id event)))))
                events))]
    (->> events
         (keep (fn [event]
                 (case (:event/type event)
                   :model/request
                   (when-not (later-result? event :model/response)
                     {:effect/type :model
                      :request/id (:request/id event)
                      :event/id (:event/id event)
                      :event/seq (:event/seq event)})

                   :repl/request
                   (when-not (later-result? event :repl/result)
                     {:effect/type :repl
                      :request/id (:request/id event)
                      :action/id (:action/id event)
                      :event/id (:event/id event)
                      :event/seq (:event/seq event)})

                   nil)))
         vec)))

(defn- ensure-open! [store]
  (when @(:closed? store)
    (throw (errors/error :journal-storage-failure "File store is closed" {}))))

(defn- ensure-root-audited! [store]
  (let [{:keys [status ids failure]} @(:audit store)]
    (case status
      :ok ids
      :failed (throw failure)
      :pending
      (try
        (let [ids (audit-event-ids (:root store))]
          (reset! (:audit store) {:status :ok :ids ids})
          ids)
        (catch Throwable cause
          (let [failure (if (= :journal-storage-failure
                               (:bbagent/error (ex-data cause)))
                          cause
                          (errors/error :journal-storage-failure
                                        "File store root audit failed"
                                        {:root (:root store)} cause))]
            (reset! (:audit store) {:status :failed :failure failure})
            (throw failure)))))))

(defn- cached-events [store session-id]
  (events (session-handle (:root store) (:cache store) session-id)))

(defrecord FileStore [root ^ConcurrentHashMap cache op-lock audit closed?
                      ^FileChannel lock-channel ^FileLock root-lock]
  store/EventStore
  (append-event! [this session-id event]
    (locking op-lock
      (ensure-open! this)
      (try
        (when-not (keyword? (:event/type event))
          (throw (errors/error :journal-storage-failure
                                "Store event requires :event/type")))
        (let [prepared (dissoc (store/prepare-event (store/strip-secrets event) 0)
                                :event/seq)]
          (when (contains? (ensure-root-audited! this) (:event/id prepared))
            (throw (errors/error :journal-storage-failure
                                 "Duplicate journal event ID"
                                 {:event/id (:event/id prepared)})))
          (let [journal (session-handle root cache session-id)
                _ (store/validate-object-references!
                   (global-blob-loader root session-id)
                   (blob-storer (:blobs journal)) prepared)
                 stored (append! journal prepared)]
            (swap! audit update :ids conj (:event/id stored))
            stored))
        (catch Throwable failure
          (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
            (throw failure)
            (throw (errors/error :journal-storage-failure
                                 "File store append failed"
                                  {:root root :session/id session-id} failure)))))))
  (events [this session-id]
    (locking op-lock
      (ensure-open! this)
      (cached-events this session-id)))
  (validate-session! [this session-id]
    (locking op-lock
      (ensure-open! this)
      (count (cached-events this session-id))))
  (recent-events [this session-id limit]
    ;; The file backend is a single-owner reference store that recovers a
    ;; session once and serves later queries from that cache, so the bounded
    ;; tail is a trim of the already-recovered vector.  The cost this avoids
    ;; is repeated whole-history decoding, which is the SQLite concern.
    (locking op-lock
      (ensure-open! this)
      (when-not (and (integer? limit) (pos? limit))
        (throw (errors/error :journal-storage-failure
                             "recent-events requires a positive limit"
                             {:limit limit})))
      (let [scanned (cached-events this session-id)]
        (vec (take-last limit scanned)))))
  (unresolved-effects [this session-id]
    (locking op-lock
      (ensure-open! this)
      (unresolved-effects-in (cached-events this session-id))))
  (events-after [this session-id event-id]
    (locking op-lock
      (ensure-open! this)
      (let [scanned (cached-events this session-id)
            index (first (keep-indexed
                          (fn [index event]
                            (when (= event-id (:event/id event)) index))
                          scanned))]
        (when (nil? index)
          (throw (errors/error :journal-storage-failure "Unknown event ID"
                               {:event/id event-id})))
        (subvec scanned (inc index)))))
  (first-event [this session-id event-type]
    (locking op-lock
      (ensure-open! this)
      (first (filter #(= event-type (:event/type %))
                     (cached-events this session-id)))))
  (latest-checkpoint [this session-id]
    (locking op-lock
      (ensure-open! this)
      (last (filter #(= :session/checkpoint (:event/type %))
                    (cached-events this session-id)))))
  (request-event [this session-id request-id]
    (locking op-lock
      (ensure-open! this)
      (first (filter #(and (= :repl/request (:event/type %))
                            (= request-id (:request/id %)))
                     (cached-events this session-id)))))
  (list-sessions [this]
    (locking op-lock
      (ensure-open! this)
      (list-sessions root)))
  store/ObjectStore
  (put-object! [this session-id value]
    (locking op-lock
      (ensure-open! this)
      (try
        (when-not (string? value)
          (throw (errors/error :journal-storage-failure
                               "Store objects must be strings")))
        (store/store-string (blob-storer (blob-directory! root session-id))
                            value)
        (catch Throwable failure
          (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
            (throw failure)
            (throw (errors/error :journal-storage-failure
                                 "File store object put failed"
                                 {:root root :session/id session-id}
                                 failure)))))))
  (get-object [this session-id digest]
    (locking op-lock
      (ensure-open! this)
      (safe-session-id! session-id)
      (let [hex (store/blob-hex digest)
            ^String content
            (when (and hex (re-matches #"[0-9a-f]{64}" hex))
              ((global-blob-loader root session-id) hex))]
      (when (nil? content)
        (throw (errors/error :session-recovery-failure
                             "Store object is missing or malformed"
                             {:blob/digest digest})))
      (when-not (= hex (coordinates/sha-256 content))
        (throw (errors/error :session-recovery-failure
                             "Store object integrity check failed"
                             {:blob/digest digest})))
         content)))
  store/StoreLifecycle
  (close-store! [_]
    (locking op-lock
      (when (compare-and-set! closed? false true)
        (try
          (.release root-lock)
          (finally
            (.close lock-channel)))))
    nil))

(defn- state-root-path [root]
  (let [^Path input (Paths/get (str root) (make-array String 0))
        ^Path normalized (.normalize (.toAbsolutePath input))]
    (create-directories! normalized)
    (.toRealPath normalized (make-array LinkOption 0))))

(defn file-store
  "Opens and exclusively locks the root-level file-backed store. Session
    journals are recovered lazily and cached; close-store! releases the lock."
  [root]
  (try
    (let [^Path root-path (state-root-path root)
          ^Path lock-path (.resolve root-path ".bbagent-file-store.lock")
          lock-file-existed? (Files/exists lock-path (make-array LinkOption 0))
          ^FileChannel channel
          (FileChannel/open lock-path
                            (into-array OpenOption [StandardOpenOption/CREATE
                                                    StandardOpenOption/WRITE]))]
      (try
        (when-not lock-file-existed?
          (force-directory! root-path))
        (let [^FileLock root-lock
              (try
                (.tryLock channel)
                (catch OverlappingFileLockException _ nil))]
          (when (nil? root-lock)
            (throw (errors/error :journal-storage-failure
                                 "File store root is already open"
                                 {:root (str root-path)})))
          (->FileStore (str root-path) (ConcurrentHashMap.) (Object.)
                       (atom {:status :pending}) (atom false)
                       channel root-lock))
        (catch Throwable failure
          (.close channel)
          (throw failure))))
    (catch Throwable failure
      (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
        (throw failure)
        (throw (errors/error :journal-storage-failure
                             "Could not open file store"
                             {:root (str root)} failure))))))
