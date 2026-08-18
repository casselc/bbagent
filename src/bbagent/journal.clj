(ns bbagent.journal
  (:require [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files LinkOption OpenOption Path Paths
            StandardCopyOption StandardOpenOption]
           [java.time Instant]
           [java.util UUID]))

(def ^:private blob-threshold-bytes 65536)
(def ^:private session-id-pattern #"[A-Za-z0-9._-]+")
(def ^:private sensitive-key-pattern
  #"(?i)^(api[-_]?key|authorization|credentials?|password|secret|token|access[-_]?token|refresh[-_]?token|oauth[-_]?token)$")

(defrecord Journal [^Path root ^Path path ^Path blobs state lock])

(defn- safe-session-id! [session-id]
  (when-not (and (string? session-id)
                 (re-matches session-id-pattern session-id)
                 (not (#{"." ".."} session-id)))
    (throw (errors/error :journal-storage-failure "Invalid session ID")))
  session-id)

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

(defn- sensitive-key? [key]
  (boolean (re-find sensitive-key-pattern
                    (if (keyword? key) (name key) (str key)))))

(defn- without-secrets [value]
  (cond
    (map? value) (into {} (keep (fn [[key item]]
                                  (when-not (sensitive-key? key)
                                    [key (without-secrets item)]))) value)
    (vector? value) (mapv without-secrets value)
    (list? value) (apply list (map without-secrets value))
    (set? value) (set (map without-secrets value))
    :else value))

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

(defn- externalize [^Path blobs value]
  (cond
    (and (string? value)
         (> (alength (.getBytes ^String value StandardCharsets/UTF_8))
            blob-threshold-bytes))
    (let [digest (coordinates/sha-256 value)
          path (.resolve blobs digest)
          bytes (.getBytes ^String value StandardCharsets/UTF_8)]
      (when-not (valid-blob? path digest bytes)
        (write-bytes! path bytes))
      (tagged-literal 'bbagent/blob
                      {:digest (str "sha256:" digest)
                       :bytes (alength bytes)
                       :encoding :utf-8}))

    (map? value) (into {} (map (fn [[key item]] [key (externalize blobs item)])) value)
    (vector? value) (mapv #(externalize blobs %) value)
    (list? value) (apply list (map #(externalize blobs %) value))
    (set? value) (set (map #(externalize blobs %) value))
    :else value))

(defn- decode-line [line]
  (let [{:journal/keys [version event checksum] :as record}
        (edn/read-string {:readers {'bbagent/blob
                                    #(tagged-literal 'bbagent/blob %)}} line)]
    (when-not (= #{:journal/version :journal/event :journal/checksum}
                 (set (keys record)))
      (throw (ex-info "Malformed journal record" {})))
    (when-not (= 1 version)
      (throw (ex-info "Unsupported journal version" {:version version})))
    (when-not (= checksum (coordinates/digest :bbagent/journal-event event))
      (throw (ex-info "Journal checksum mismatch" {})))
    event))

(defn- blob-reference? [value]
  (and (instance? clojure.lang.TaggedLiteral value)
       (= 'bbagent/blob (:tag value))))

(defn- hydrate [^Path blobs value]
  (cond
    (blob-reference? value)
    (let [reference (:form value)
          _ (when-not (= #{:digest :bytes :encoding} (set (keys reference)))
              (throw (ex-info "Malformed journal blob reference" {})))
          digest (:digest reference)
          hex (when (str/starts-with? digest "sha256:") (subs digest 7))
          path (when hex (.resolve blobs hex))]
      (when-not (and (= :utf-8 (:encoding reference))
                     (integer? (:bytes reference))
                     hex (re-matches #"[0-9a-f]{64}" hex)
                     (Files/isRegularFile path (make-array LinkOption 0)))
        (throw (ex-info "Journal blob is missing or malformed"
                        {:blob/digest digest})))
      (let [bytes (Files/readAllBytes path)
            content (String. bytes StandardCharsets/UTF_8)]
        (when-not (and (= (:bytes reference) (alength bytes))
                       (= hex (coordinates/sha-256 content)))
          (throw (ex-info "Journal blob integrity check failed"
                          {:blob/digest digest})))
        content))

    (map? value) (into {} (map (fn [[key item]] [key (hydrate blobs item)])) value)
    (vector? value) (mapv #(hydrate blobs %) value)
    (list? value) (apply list (map #(hydrate blobs %) value))
    (set? value) (set (map #(hydrate blobs %) value))
    :else value))

(defn recover [path]
  (let [^Path path (Paths/get (str path) (make-array String 0))]
    (if-not (Files/exists path (make-array LinkOption 0))
      {:events [] :valid-lines [] :tail-discarded? false}
      (let [text (slurp (str path))
            terminated? (str/ends-with? text "\n")
            parts (str/split text #"\n" -1)
            lines (vec (butlast parts))
            tail-discarded? (and (not terminated?) (boolean (seq (last parts))))
            blobs (.resolve (.getParent path) "blobs")]
        (try
          (loop [remaining lines
                 expected-seq 1
                 stored-events []
                 valid-lines []]
            (if-let [line (first remaining)]
              (do
                (when (str/blank? line)
                  (throw (ex-info "Blank journal record" {})))
                (let [event (decode-line line)]
                  (when-not (= expected-seq (:event/seq event))
                    (throw (ex-info "Journal event sequence is discontinuous"
                                    {:expected expected-seq
                                     :actual (:event/seq event)})))
                  (recur (subvec remaining 1) (inc expected-seq)
                         (conj stored-events event) (conj valid-lines line))))
              {:events (mapv #(hydrate blobs %) stored-events)
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
        (->Journal directory path blobs (atom {:events events :next-seq next-seq})
                   (Object.))))
    (catch clojure.lang.ExceptionInfo failure
      (throw failure))
    (catch Throwable failure
      (throw (errors/error :journal-storage-failure "Could not open journal"
                           {:root (str root) :session/id session-id} failure)))))

(defn append! [^Journal journal event]
  (locking (:lock journal)
    (try
      (let [seq-number (inc (:next-seq @(:state journal)))
            event (-> event
                      without-secrets
                      (assoc :event/id (or (:event/id event) (str (UUID/randomUUID)))
                             :event/seq seq-number
                             :event/time (or (:event/time event) (str (Instant/now)))))
            stored-event (externalize (:blobs journal) event)
            record {:journal/version 1
                    :journal/event stored-event
                    :journal/checksum
                    (coordinates/digest :bbagent/journal-event stored-event)}
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
                            :next-seq seq-number}))
        event)
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
