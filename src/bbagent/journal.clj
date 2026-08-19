(ns bbagent.journal
  (:require [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
            [bbagent.store :as store]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
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

(defn recover [path]
  (let [^Path path (Paths/get (str path) (make-array String 0))]
    (if-not (Files/exists path (make-array LinkOption 0))
      {:events [] :valid-lines [] :tail-discarded? false}
      (let [text (slurp (str path))
            terminated? (str/ends-with? text "\n")
            parts (str/split text #"\n" -1)
            lines (vec (butlast parts))
            tail-discarded? (and (not terminated?) (boolean (seq (last parts))))
            blobs (.resolve (.getParent path) "blobs")
            load-blob (blob-loader blobs)]
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
              {:events (mapv #(store/hydrate load-blob %) stored-events)
               :valid-lines valid-lines
               :tail-discarded? tail-discarded?}))
          (catch Throwable failure
            (throw (errors/error :session-recovery-failure
                                 "Journal integrity check failed"
                                 {:path (str path)} failure))))))))

(defn open! [root session-id]
  (try
    (let [directory (session-path root session-id)
          blobs (.resolve directory "blobs")
          path (.resolve directory "events.edn")]
      (Files/createDirectories blobs (make-array java.nio.file.attribute.FileAttribute 0))
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
        (let [stored-event (store/externalize (blob-storer (:blobs journal))
                                               event)
              record {:journal/version 1
                      :journal/event stored-event
                      :journal/checksum
                      (store/semantic-checksum stored-event)}
              bytes (.getBytes (str (pr-str record) "\n") StandardCharsets/UTF_8)]
          (with-open [channel (java.nio.channels.FileChannel/open
                               (:path journal)
                               (into-array OpenOption
                                           [StandardOpenOption/CREATE
                                            StandardOpenOption/WRITE
                                            StandardOpenOption/APPEND]))]
            (let [buffer (ByteBuffer/wrap bytes)]
              (while (.hasRemaining buffer) (.write channel buffer)))
            (.force channel true))
          (swap! (:state journal)
                 (fn [state] {:events (conj (:events state) event)
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
             (map #(str (.getFileName ^Path %)))
             sort
             vec)))))

(defn- blob-directory! [root session-id]
  (let [blobs (.resolve (session-path root session-id) "blobs")]
    (Files/createDirectories blobs (make-array java.nio.file.attribute.FileAttribute 0))
    blobs))

(defn- session-handle [root ^ConcurrentHashMap cache session-id]
  (let [session-id (safe-session-id! session-id)]
    (or (.get cache session-id)
        (let [journal (open! root session-id)]
          (or (.putIfAbsent cache session-id journal) journal)))))

(defn- stored-event-ids [root]
  (into #{}
        (mapcat (fn [session-id]
                  (keep :event/id (read-events root session-id))))
        (list-sessions root)))

(defn- logical-session-ids [root]
  (->> (list-sessions root)
       (filter #(seq (read-events root %)))
       vec))

(defn- global-blob-loader [root session-id]
  (let [session-ids (cons session-id (remove #{session-id}
                                              (list-sessions root)))]
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

(defrecord FileStore [root ^ConcurrentHashMap cache lock ids]
  store/EventStore
  (append-event! [_ session-id event]
    (locking lock
      (try
        (when-not (keyword? (:event/type event))
          (throw (errors/error :journal-storage-failure
                               "Store event requires :event/type")))
        (let [prepared (dissoc (store/prepare-event (store/strip-secrets event) 0)
                               :event/seq)]
          (when (contains? @ids (:event/id prepared))
            (throw (errors/error :journal-storage-failure
                                 "Duplicate journal event ID"
                                 {:event/id (:event/id prepared)})))
          (let [journal (session-handle root cache session-id)
                _ (store/validate-object-references!
                   (global-blob-loader root session-id)
                   (blob-storer (:blobs journal)) prepared)
                stored (append! journal prepared)]
            (swap! ids conj (:event/id stored))
            stored))
        (catch Throwable failure
          (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
            (throw failure)
            (throw (errors/error :journal-storage-failure
                                 "File store append failed"
                                 {:root root :session/id session-id} failure)))))))
  (events [_ session-id]
    (locking lock (read-events root session-id)))
  (validate-session! [_ session-id]
    (locking lock (count (read-events root session-id))))
  (unresolved-effects [_ session-id]
    (locking lock (unresolved-effects-in (read-events root session-id))))
  (events-after [_ session-id event-id]
    (locking lock
      (let [scanned (read-events root session-id)
            index (first (keep-indexed
                          (fn [index event]
                            (when (= event-id (:event/id event)) index))
                          scanned))]
        (when (nil? index)
          (throw (errors/error :journal-storage-failure "Unknown event ID"
                               {:event/id event-id})))
        (subvec scanned (inc index)))))
  (first-event [_ session-id event-type]
    (locking lock
      (first (filter #(= event-type (:event/type %))
                     (read-events root session-id)))))
  (latest-checkpoint [_ session-id]
    (locking lock
      (last (filter #(= :session/checkpoint (:event/type %))
                    (read-events root session-id)))))
  (request-event [_ session-id request-id]
    (locking lock
      (first (filter #(and (= :repl/request (:event/type %))
                           (= request-id (:request/id %)))
                     (read-events root session-id)))))
  (list-sessions [_]
    (locking lock (logical-session-ids root)))
  store/ObjectStore
  (put-object! [_ session-id value]
    (locking lock
      (when-not (string? value)
        (throw (errors/error :journal-storage-failure
                             "Store objects must be strings")))
      (store/store-string (blob-storer (blob-directory! root session-id))
                          value)))
  (get-object [_ session-id digest]
    (locking lock
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
  (close-store! [_] nil))

(defn file-store
  "Opens the root-level file-backed store.  Session journal handles are
    cached; close-store! is an idempotent no-op."
  [root]
  (->FileStore (str root) (ConcurrentHashMap.) (Object.)
               (atom (stored-event-ids root))))
