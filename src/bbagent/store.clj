(ns bbagent.store
  "Backend-neutral S0b storage contract and pure event/object helpers.

  The protocols operate on a root-level store and take a session-id where
  an operation is scoped to one session.  The pure helpers capture the
  exact A0 journal semantics (secret stripping, event preparation, large
  string externalization, hydration, semantic checksums, and canonical
  payload encoding) so any backend can reproduce them byte for byte."
  (:require [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]
           [java.util UUID]))

(defprotocol EventStore
  "Root-level durable event operations keyed by session."
  (append-event! [store session-id event]
    "Durably appends event to the session log.  Assigns :event/id,
     :event/seq, and :event/time when absent, strips secrets, assigns a
     contiguous sequence number, and rejects duplicate event IDs.
     Returns the stored event.")
  (events [store session-id]
    "Returns the session's events in :event/seq order.")
  (validate-session! [store session-id]
    "Validates the complete immutable session history and returns its event count.")
  (unresolved-effects [store session-id]
    "Returns durable model or REPL requests with no later correlated result.")
  (events-after [store session-id event-id]
    "Returns the events stored after the event identified by event-id.
     Throws when the event ID is unknown to the session.")
  (first-event [store session-id event-type]
    "Returns the first event with the given :event/type, or nil.")
  (latest-checkpoint [store session-id]
    "Returns the most recent :session/checkpoint event, or nil.")
  (request-event [store session-id request-id]
    "Returns the earliest :repl/request carrying the request ID, or nil.")
  (list-sessions [store]
    "Returns the sorted session IDs present in the store."))

(defprotocol ObjectStore
  "Root-level content-addressed object operations keyed by session."
  (put-object! [store session-id value]
    "Durably stores a UTF-8 string object and returns its #bbagent/blob
     reference.")
  (get-object [store session-id digest]
    "Returns the verified string object for a sha256:<hex> digest.
     Fails with :session-recovery-failure when it is missing or corrupt."))

(defprotocol StoreLifecycle
  "Store lifecycle operations."
  (close-store! [store]
    "Releases store resources.  Idempotent."))

(def blob-threshold-bytes
  "UTF-8 strings larger than this many bytes are stored as objects."
  65536)

(def ^:private session-id-pattern #"[A-Za-z0-9._-]+")

(defn validate-session-id! [session-id]
  (when-not (and (string? session-id)
                 (re-matches session-id-pattern session-id)
                 (not (#{"." ".."} session-id)))
    (throw (errors/error :journal-storage-failure "Invalid session ID")))
  session-id)

(def ^:private sensitive-key-pattern
  #"(?i)^(api[-_]?key|authorization|credentials?|password|secret|token|access[-_]?token|refresh[-_]?token|oauth[-_]?token)$")

(defn- sensitive-key? [key]
  (boolean (re-find sensitive-key-pattern
                    (if (keyword? key) (name key) (str key)))))

(defn strip-secrets
  "Removes sensitive map entries at any depth, exactly as the A0 journal."
  [value]
  (cond
    (map? value) (into {} (keep (fn [[key item]]
                                  (when-not (sensitive-key? key)
                                    [key (strip-secrets item)]))) value)
    (vector? value) (mapv strip-secrets value)
    (list? value) (apply list (map strip-secrets value))
    (set? value) (set (map strip-secrets value))
    :else value))

(defn prepare-event
  "Assigns :event/id, :event/seq, and :event/time when absent."
  [event seq-number]
  (assoc event
         :event/id (or (:event/id event) (str (UUID/randomUUID)))
         :event/seq seq-number
         :event/time (or (:event/time event) (str (Instant/now)))))

(defn semantic-checksum
  "Checksums a stored (externalized) journal event."
  [event]
  (coordinates/digest :bbagent/journal-event event))

(defn blob-reference?
  "True when value is a #bbagent/blob tagged literal."
  [value]
  (and (instance? clojure.lang.TaggedLiteral value)
       (= 'bbagent/blob (:tag value))))

(defn blob-hex
  "Extracts the hex digest from a sha256:<hex> blob digest string."
  [digest]
  (when (str/starts-with? digest "sha256:") (subs digest 7)))

(defn store-string
  "Stores value through (store-blob digest-hex value) and returns its
   #bbagent/blob reference."
  [store-blob ^String value]
  (let [digest (coordinates/sha-256 value)
        bytes (.getBytes value StandardCharsets/UTF_8)]
    (store-blob digest value)
    (tagged-literal 'bbagent/blob
                    {:digest (str "sha256:" digest)
                     :bytes (alength bytes)
                     :encoding :utf-8})))

(defn externalize
  "Replaces over-threshold strings with #bbagent/blob references at any
   depth, storing bytes through (store-blob digest-hex value)."
  [store-blob value]
  (cond
    (and (string? value)
         (> (alength (.getBytes ^String value StandardCharsets/UTF_8))
            blob-threshold-bytes))
    (store-string store-blob value)

    (map? value)
    (into {} (map (fn [[key item]] [key (externalize store-blob item)])) value)
    (vector? value) (mapv #(externalize store-blob %) value)
    (list? value) (apply list (map #(externalize store-blob %) value))
    (set? value) (set (map #(externalize store-blob %) value))
    :else value))

(defn hydrate
  "Resolves #bbagent/blob references at any depth through
   (load-blob digest-hex), which must return the stored string or nil
   when the object is missing.  Verifies reference structure, byte
   length, and content digest."
  [load-blob value]
  (cond
    (blob-reference? value)
    (let [reference (:form value)
          _ (when-not (= #{:digest :bytes :encoding} (set (keys reference)))
              (throw (ex-info "Malformed journal blob reference" {})))
          digest (:digest reference)
          hex (blob-hex digest)
          ^String content
          (when (and (= :utf-8 (:encoding reference))
                     (integer? (:bytes reference))
                     hex (re-matches #"[0-9a-f]{64}" hex))
            (load-blob hex))]
      (when (nil? content)
        (throw (ex-info "Journal blob is missing or malformed"
                        {:blob/digest digest})))
      (when-not (and (= (:bytes reference)
                        (alength (.getBytes content StandardCharsets/UTF_8)))
                     (= hex (coordinates/sha-256 content)))
        (throw (ex-info "Journal blob integrity check failed"
                        {:blob/digest digest})))
      content)

    (map? value)
    (into {} (map (fn [[key item]] [key (hydrate load-blob item)])) value)
    (vector? value) (mapv #(hydrate load-blob %) value)
    (list? value) (apply list (map #(hydrate load-blob %) value))
    (set? value) (set (map #(hydrate load-blob %) value))
    :else value))

(defn- object-references [value]
  (cond
    (blob-reference? value) [value]
    (map? value) (mapcat (comp object-references val) value)
    (or (vector? value) (list? value) (set? value))
    (mapcat object-references value)
    :else []))

(defn validate-object-references!
  "Fails unless every caller-supplied #bbagent/blob reference resolves and verifies."
  ([load-blob value]
   (validate-object-references! load-blob (fn [_ _]) value))
  ([load-blob on-reference value]
   (doseq [reference (object-references value)]
     (let [content (hydrate load-blob reference)
           digest (blob-hex (:digest (:form reference)))]
       (on-reference digest content)))
   nil))

(defn encode-payload
  "Encodes supported Clojure data as a canonical, order-independent
   string using coordinates/canonical-string."
  [value]
  (coordinates/canonical-string value))

(defn- realize-tree [tree]
  (if-not (and (vector? tree) (keyword? (first tree)))
    (throw (ex-info "Malformed canonical payload" {}))
    (case (first tree)
      :nil (if (= 1 (count tree)) nil
               (throw (ex-info "Malformed canonical payload" {})))
      :boolean (let [value (second tree)]
                 (if (and (= 2 (count tree)) (boolean? value)) value
                     (throw (ex-info "Malformed canonical payload" {}))))
      :string (let [value (second tree)]
                (if (and (= 2 (count tree)) (string? value)) value
                    (throw (ex-info "Malformed canonical payload" {}))))
      :character (let [value (second tree)]
                   (if (and (= 2 (count tree)) (string? value) (= 1 (count value)))
                     (first value)
                     (throw (ex-info "Malformed canonical payload" {}))))
      :keyword (if (= 3 (count tree))
                 (keyword (nth tree 1) (nth tree 2))
                 (throw (ex-info "Malformed canonical payload" {})))
      :symbol (if (= 3 (count tree))
                (symbol (nth tree 1) (nth tree 2))
                (throw (ex-info "Malformed canonical payload" {})))
      :integer (let [value (when (= 2 (count tree))
                             (edn/read-string (second tree)))]
                 (if (integer? value) value
                     (throw (ex-info "Malformed canonical payload" {}))))
      :tagged-literal (tagged-literal (symbol (second tree))
                                      (realize-tree (nth tree 2)))
      :map (into {} (map (fn [[key item]]
                           [(realize-tree key) (realize-tree item)]))
                 (second tree))
      :vector (mapv realize-tree (second tree))
      :list (apply list (map realize-tree (second tree)))
      :set (set (map realize-tree (second tree)))
      (throw (ex-info "Malformed canonical payload" {})))))

(defn decode-payload
  "Decodes an encode-payload string back into the original value."
  [^String encoded]
  (realize-tree (edn/read-string encoded)))
