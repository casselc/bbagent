(ns bbagent.spi
  "The repository-neutral ExecutionEnvironment EDN SPI: the envelope keeper.

  bb4t's ExecutionEnvironment is a Clojure protocol, which makes it a
  contract between two namespaces loaded in one process.  An SPI that has to
  survive a repository boundary -- one repo's harness reading what another
  repo's executor produced -- cannot be a protocol; it has to be data, and
  data that two independent implementations render byte-identically or
  cannot be compared at all.

  This namespace is the single authority on the bbagent side for the three
  things that makes such a comparison possible:

  - the envelope shapes (four kinds, versioned once, exact keys);
  - the canonical EDN rendering, which is deterministic down to the byte;
  - the coordinate algorithm, which names an envelope's payload.

  It is deliberately independent: it requires nothing outside Clojure
  itself, so it can be lifted into another repository verbatim, and its
  coordinates are domain-separated from both bb4t's and bbagent's, so an
  SPI coordinate can never be mistaken for one of theirs over the same
  data.

  What the envelopes describe is settled elsewhere and only projected
  here.  A run envelope restates what the SmolVM executor and worker
  already return -- the statuses bb4t recognizes, an exit only when the
  workload actually exited, an input coordinate only when the project did
  not move, a refusal rather than a euphemism.  Nothing here widens what
  the substrate claims; it gives existing claims a spelling that crosses
  repositories.

  The normative statement of these rules is
  docs/architecture/0003-execution-environment-edn-spi.md.  The byte-level
  conformance fixtures live under test/fixtures/spi-v1/ with a golden
  SHA-256 beside each one; the conformance suite renders the ported
  evidence inputs and compares bytes."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def envelope-version
  "The one envelope version there has ever been.  A change to any rule in
  this namespace that alters the rendering of existing envelopes is a new
  version, not a patch; coordinates are digests over rendered bytes, so
  silent drift would rename nothing and invalidate everything."
  1)

(def kinds
  "The envelope kinds.  Four, one per thing the SPI has found it needs to
  say: what an environment is, whether one can be had at all, what a run
  produced, and what a replay restored."
  #{:spi.environment/describe
    :spi.environment/availability
    :spi.execution/run
    :spi.execution/replay})

(def refusal-categories
  "Why an execution environment could not be had.  Derived from the
  executor's own refusal points, none of which is invented here: no
  manager, a manager whose isolation nobody has measured, a guest image
  that is not usable, a guest image that is not the one pinned, and a
  project with no unprivileged identity to run as.  The last bucket is for
  a refusal this catalogue does not name, which is a refusal all the
  same."
  #{:spi.refusal/manager-unavailable
    :spi.refusal/manager-unmeasured
    :spi.refusal/guest-image-unusable
    :spi.refusal/guest-image-digest-mismatch
    :spi.refusal/project-identity
    :spi.refusal/unknown})

(def worker-statuses
  "The statuses a worker reports for a run it attempted.  These are the
  substrate's own vocabulary; :project-changed is not among them because
  it is a conclusion about the input, derived above the worker."
  #{:completed :timeout :worker-failure})

(def run-statuses
  "The statuses a run envelope may carry.  :project-changed is a
  :completed run that cannot verify anything, because the project moved
  while it ran; it is kept apart so nothing can pattern-match an
  unanchored run against an ordinary success."
  (conj worker-statuses :project-changed))

(def dispositions
  "What happened to the machine a run happened in.  Every machine is
  ephemeral and every machine is destroyed, so :terminated is the one
  disposition the substrate has ever produced.  When that stops being
  true, the set grows and the envelope version moves with it."
  #{:terminated})

(def ^:private coordinate-pattern
  "sha256 coordinates, lowercase hex, exactly as digest produces them."
  #"^sha256:[0-9a-f]{64}$")

;; ---------------------------------------------------------------------------
;; Canonical rendering

(def ^:private name-characters
  "Characters a keyword or symbol may be made of, per EDN.

  A keyword whose name contains a space renders as something no EDN reader
  reads back, and byte-identity over unreadable bytes is worthless, so the
  domain refuses such names rather than printing them."
  #"^[A-Za-z0-9*+!_?$%&<>=./#-]*$")

(defn- fail! [kind message data]
  (throw (ex-info message (assoc data :spi/error kind))))

(defn- named-ok? [text]
  ;; A nil namespace is a simple name, which is fine; an unspellable one
  ;; is not.
  (or (nil? text)
      (and (string? text) (re-matches name-characters text))))

(defn- readable
  "One scalar exactly as Clojure prints it readably.

  The binding is explicit because *print-readably* is what keeps a newline
  inside a string a backslash-n on the wire instead of an actual line
  break, and because a caller's *print-length* must not be able to
  silently truncate an envelope."
  [^Object value]
  (binding [*print-length* nil
            *print-level* nil
            *print-readably* true
            *print-dup* false]
    (pr-str value)))

(declare render)

(defn- render-map-entries [value]
  (->> value
       (map (fn [entry]
              {:key (render (key entry))
               :entry (str (render (key entry)) " " (render (val entry)))}))
       (sort-by :key)
       (map :entry)
       (str/join ", ")))

(defn render
  "The canonical EDN rendering of an inert value, as a string.

  Deterministic by construction, which is the whole point:

  - maps render with their entries sorted ascending by the rendered key
    text, so map order in memory cannot reach the bytes;
  - sets render sorted by rendered element text, for the same reason;
  - vectors and lists keep their order, which is information;
  - scalars render as plain EDN (strings readably, integers in decimal,
    keywords and symbols in name form);
  - elements and entries are separated by \", \", a map entry by \" \".

  Anything outside the inert domain -- floats, records, metadata, objects,
  keywords with characters EDN cannot spell -- is refused rather than
  printed, because two implementations that render a float differently
  would agree on everything and compare on nothing."
  [value]
  (when (and (instance? clojure.lang.IMeta value) (seq (meta value)))
    (fail! :non-inert "Envelope data must not carry metadata"
           {:value/type (some-> value class .getName)}))
  (cond
    (nil? value) "nil"
    (boolean? value) (str value)
    (integer? value) (str value)
    (string? value) (readable value)
    (char? value) (readable value)
    (keyword? value)
    (let [namespace (namespace value) name (name value)]
      (when-not (and (named-ok? namespace) (named-ok? name))
        (fail! :non-inert "Keyword is outside the canonical EDN domain"
               {:value/type (some-> value class .getName)}))
      (str value))
    (symbol? value)
    (let [namespace (namespace value) name (name value)]
      (when-not (and (named-ok? namespace) (named-ok? name))
        (fail! :non-inert "Symbol is outside the canonical EDN domain"
               {:value/type (some-> value class .getName)}))
      (str value))
    (record? value)
    (fail! :non-inert "Records are not envelope data"
           {:value/type (some-> value class .getName)})
    (map? value) (str "{" (render-map-entries value) "}")
    (vector? value)
    (str "[" (str/join ", " (mapv render value)) "]")
    (set? value)
    (str "#{" (->> value (map render) sort (str/join ", ")) "}")
    (sequential? value)
    (str "(" (str/join ", " (mapv render value)) ")")
    :else (fail! :non-inert "Value is outside the canonical EDN domain"
                 {:value/type (some-> value class .getName)})))

(defn inert?
  "True when render would accept the value.  A total predicate over the
  same domain render enforces, so a caller can check before committing to
  a context where throwing would be rude."
  [value]
  (try (render value) true (catch Exception _ false)))

(defn sha-256
  "The SHA-256 of a string's UTF-8 bytes, lowercase hex.

  Here rather than in a shared namespace on purpose: the keeper is the one
  thing another repository should be able to lift whole, and four lines of
  MessageDigest cost less than a dependency that reaches back into this
  one."
  [^String value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256")
                       (.getBytes value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn coordinate
  "The SPI coordinate of a payload: a domain-separated SHA-256 over the
  canonical rendering.

  The domain tag [:spi.coordinate/v1 kind payload] is rendered and hashed,
  so the coordinate depends on the rendering (which is canonical), on the
  kind (so a description and a refusal can never share a coordinate), and
  on nothing else -- not on the JVM, the locale, or which repository
  computed it.  The tag also makes an SPI coordinate unequal to a bb4t or
  bbagent coordinate over identical data, which is a property, not an
  accident: they name different things."
  [kind payload]
  (when-not (qualified-keyword? kind)
    (fail! :coordinate-invalid "Coordinate kind must be a qualified keyword"
           {:coordinate/kind kind}))
  (str "sha256:" (sha-256 (render [:spi.coordinate/v1 kind payload]))))

(defn environment-coordinate
  "The coordinate of an environment's description.  What an environment
  is, named once, so every envelope that attributes a run to it can name
  the same thing."
  [description]
  (coordinate :spi.environment/description description))

;; ---------------------------------------------------------------------------
;; Envelope frames

(def ^:private frame-keys #{:spi/version :spi/kind})

(defn- frame!
  "The common envelope frame, checked before any kind-specific rule."
  [envelope]
  (when-not (map? envelope)
    (fail! :envelope-invalid "An envelope must be a map"
           {:envelope/type (some-> envelope class .getName)}))
  (when-not (= envelope-version (:spi/version envelope))
    (fail! :envelope-invalid "Unknown envelope version"
           {:spi/version (:spi/version envelope)}))
  (when-not (contains? kinds (:spi/kind envelope))
    (fail! :envelope-invalid "Unknown envelope kind"
           {:spi/kind (:spi/kind envelope)}))
  envelope)

(defn- exact-keys!
  [envelope required optional]
  (let [present (set (keys envelope))
        missing (not-empty (set/difference required present))
        extra (not-empty (set/difference present required optional))]
    (when missing
      (fail! :envelope-invalid "Envelope is missing required keys"
             {:spi/kind (:spi/kind envelope) :missing missing}))
    (when extra
      (fail! :envelope-invalid "Envelope carries keys its kind does not have"
             {:spi/kind (:spi/kind envelope) :extra extra})))
  envelope)

(defn- inert!
  [envelope]
  (render envelope)
  envelope)

(defn- positive-integer!
  [envelope key]
  (when-not (and (integer? (get envelope key)) (pos? (get envelope key)))
    (fail! :envelope-invalid (str "Expected a positive integer at " key)
           {:spi/kind (:spi/kind envelope) key (get envelope key)})))

(defn- non-negative-integer!
  [envelope key]
  (when-not (and (integer? (get envelope key)) (not (neg? (get envelope key))))
    (fail! :envelope-invalid (str "Expected a non-negative integer at " key)
           {:spi/kind (:spi/kind envelope) key (get envelope key)})))

(defn- coordinate!
  [envelope key]
  (when-not (and (string? (get envelope key))
                 (re-matches coordinate-pattern (get envelope key)))
    (fail! :envelope-invalid (str "Expected a sha256 coordinate at " key)
           {:spi/kind (:spi/kind envelope) key (get envelope key)})))

;; -- describe --------------------------------------------------------------

(defn- validate-description!
  [envelope]
  (let [description (:environment/description envelope)]
    (when-not (and (map? description) (seq description))
      (fail! :envelope-invalid "An environment description must be a non-empty map"
             {:spi/kind (:spi/kind envelope)}))
    (let [actual (get envelope :environment/coordinate)
          expected (environment-coordinate description)]
      (when-not (= expected actual)
        (fail! :envelope-invalid
               "The coordinate does not name the description it sits beside"
               {:spi/kind (:spi/kind envelope)
                :environment/coordinate actual
                :expected expected})))
    envelope))

;; -- availability ----------------------------------------------------------

(def ^:private refusal-keys #{:refusal/category :refusal/reason})

(defn- validate-refusal!
  [refusal]
  (when-not (and (map? refusal)
                 (= refusal-keys (set (keys refusal)))
                 (contains? refusal-categories (:refusal/category refusal))
                 (string? (:refusal/reason refusal))
                 (seq (:refusal/reason refusal)))
    (fail! :envelope-invalid "A refusal names a known category and a reason"
           {:refusal refusal}))
  refusal)

(defn- validate-availability!
  [envelope]
  (let [available? (:environment/available? envelope)]
    (when-not (boolean? available?)
      (fail! :envelope-invalid "Availability must be a boolean"
             {:spi/kind (:spi/kind envelope)}))
    (if available?
      (do (coordinate! envelope :environment/coordinate)
          (when (contains? envelope :environment/refusal)
            (fail! :envelope-invalid
                   "An available environment carries no refusal"
                   {:spi/kind (:spi/kind envelope)})))
      (do (validate-refusal! (:environment/refusal envelope))
          (when (contains? envelope :environment/coordinate)
            (fail! :envelope-invalid
                   "A refused environment carries no coordinate"
                   {:spi/kind (:spi/kind envelope)}))))
    envelope))

;; -- run -------------------------------------------------------------------

(def ^:private stream-keys #{:stream/text :stream/bytes :stream/truncated?})

(defn- validate-stream!
  [envelope key]
  (let [stream (get envelope key)]
    (when-not (and (map? stream) (= stream-keys (set (keys stream)))
                   (string? (:stream/text stream))
                   (integer? (:stream/bytes stream))
                   (not (neg? (:stream/bytes stream)))
                   (boolean? (:stream/truncated? stream)))
      (fail! :envelope-invalid "A stream is text, a true byte count, and a flag"
             {:spi/kind (:spi/kind envelope) key stream}))
    envelope))

(defn- validate-input!
  [envelope]
  (let [input (:run/input envelope)]
    (when-not (map? input)
      (fail! :envelope-invalid "A run names its input or admits it moved"
             {:spi/kind (:spi/kind envelope) :run/input input}))
    (cond
      (= #{:input/coordinate} (set (keys input)))
      (when-not (and (string? (:input/coordinate input))
                     (re-matches coordinate-pattern (:input/coordinate input)))
        (fail! :envelope-invalid "An input coordinate is a sha256 string"
               {:spi/kind (:spi/kind envelope) :run/input input}))

      (= #{:input/stability} (set (keys input)))
      (when-not (= :input/project-changed (:input/stability input))
        (fail! :envelope-invalid "Unknown input stability"
               {:spi/kind (:spi/kind envelope) :run/input input}))

      :else (fail! :envelope-invalid
                   "A run input is one of :input/coordinate or :input/stability"
                   {:spi/kind (:spi/kind envelope)
                    :keys (set (keys input))})))
  envelope)

(def ^:private attribution-keys #{:environment/coordinate :environment/type})

(defn- validate-run!
  [envelope]
  (let [status (:output/status envelope)]
    (when-not (contains? run-statuses status)
      (fail! :envelope-invalid "Unknown run status"
             {:spi/kind (:spi/kind envelope) :output/status status}))
    (let [attribution (:run/attribution envelope)]
      (when-not (and (map? attribution)
                     (= attribution-keys (set (keys attribution)))
                     (keyword? (:environment/type attribution)))
        (fail! :envelope-invalid
               "A run is attributed to one environment, by coordinate and type"
               {:spi/kind (:spi/kind envelope) :run/attribution attribution}))
      (coordinate! attribution :environment/coordinate))
    (positive-integer! envelope :run/invocation-index)
    (when-not (contains? dispositions (:run/disposition envelope))
      (fail! :envelope-invalid "Unknown disposition"
             {:spi/kind (:spi/kind envelope)
              :run/disposition (:run/disposition envelope)}))
    (non-negative-integer! envelope :output/duration-ms)
    (validate-input! envelope)
    ;; A changed project and a moved input are the same fact about the
    ;; same run, and the envelope must say both or neither: an
    ;; unanchored run that also named a coordinate would be exactly the
    ;; lie the demotion rules exist to prevent.
    (let [changed? (= :project-changed status)
          says-changed? (= :input/project-changed
                           (:input/stability (:run/input envelope)))]
      (when (not= changed? says-changed?)
        (fail! :envelope-invalid
               (str "A changed project and a moved input are the same "
                    "fact; the envelope must say both or neither")
               {:spi/kind (:spi/kind envelope)
                :output/status status
                :run/input (:run/input envelope)})))
    (validate-stream! envelope :output/stdout)
    (validate-stream! envelope :output/stderr)
    ;; An exit survives only when the workload actually exited, and the
    ;; only status that says it did is :completed.  A deadline is not a
    ;; program that chose a number, and a changed project is not a
    ;; verification that finished.
    (if (= :completed status)
      (when-not (integer? (:output/exit envelope))
        (fail! :envelope-invalid "A completed run carries its exit"
               {:spi/kind (:spi/kind envelope)}))
      (when (contains? envelope :output/exit)
        (fail! :envelope-invalid "Only a completed run carries an exit"
               {:spi/kind (:spi/kind envelope) :output/status status})))
    ;; A run whose project moved demotes its process outcome, so no reader
    ;; can match an unanchored run against ordinary success.  The demotion
    ;; is mandatory, not decorative.
    (if (= :project-changed status)
      (let [process (:output/process envelope)]
        (when-not (and (map? process)
                       (contains? worker-statuses (:process/status process)))
          (fail! :envelope-invalid
                 "A changed project demotes its process outcome"
                 {:spi/kind (:spi/kind envelope) :output/process process}))
        (when (and (contains? process :process/exit)
                   (not (integer? (:process/exit process))))
          (fail! :envelope-invalid "A demoted exit is an integer or absent"
                 {:spi/kind (:spi/kind envelope) :output/process process}))
        (when (and (not (contains? process :process/exit))
                   (= :completed (:process/status process)))
          (fail! :envelope-invalid "A completed process carries its exit"
                 {:spi/kind (:spi/kind envelope) :output/process process})))
      (when (contains? envelope :output/process)
        (fail! :envelope-invalid
               "Only a changed project demotes its process outcome"
               {:spi/kind (:spi/kind envelope)})))
    (when (contains? envelope :output/error)
      (when-not (and (string? (:output/error envelope))
                     (= :worker-failure status))
        (fail! :envelope-invalid
               "A run error belongs to a worker failure"
               {:spi/kind (:spi/kind envelope) :output/status status})))
    envelope))

;; -- replay ----------------------------------------------------------------

(defn- validate-replay!
  [envelope]
  (positive-integer! envelope :replay/invocation-index)
  (non-negative-integer! envelope :replay/invocation-count)
  (when (> (:replay/invocation-index envelope)
           (:replay/invocation-count envelope))
    (fail! :envelope-invalid
           (str "A replay's recorded index cannot exceed the environment's "
                "invocation count; a faithful replay never moves the counter")
           {:spi/kind (:spi/kind envelope)
            :replay/invocation-index (:replay/invocation-index envelope)
            :replay/invocation-count (:replay/invocation-count envelope)}))
  envelope)

(def ^:private kind-keys
  {:spi.environment/describe
   {:required #{:environment/description :environment/coordinate}
    :optional #{}}

   :spi.environment/availability
   {:required #{:environment/available?}
    ;; Exactly one of these belongs, which validate-availability! decides;
    ;; to exact-keys! they are the optional halves of one either/or.
    :optional #{:environment/coordinate :environment/refusal}}

   :spi.execution/run
   {:required #{:run/invocation-index :run/attribution :run/input
                :output/status :output/stdout :output/stderr
                :output/duration-ms :run/disposition}
    :optional #{:output/exit :output/process :output/error}}

   :spi.execution/replay
   {:required #{:replay/invocation-index :replay/invocation-count}
    :optional #{}}})

(defn validate
  "Checks an envelope against every rule of its kind, or fails closed.

  Returns the envelope.  Validation covers the frame (version, kind), the
  exact key set, inertness of the whole value, and each kind's own
  semantics -- including that a describe envelope's coordinate really is
  the coordinate of the description beside it, which is what makes a
  misattributed envelope a detectable lie rather than an odd-looking
  string."
  [envelope]
  (frame! envelope)
  (let [{:keys [required optional]} (get kind-keys (:spi/kind envelope))]
    (exact-keys! (inert! envelope)
                 (into frame-keys required)
                 optional))
  (case (:spi/kind envelope)
    :spi.environment/describe (validate-description! envelope)
    :spi.environment/availability (validate-availability! envelope)
    :spi.execution/run (validate-run! envelope)
    :spi.execution/replay (validate-replay! envelope))
  envelope)

(defn envelope?
  "True when the value is a valid envelope of any kind."
  [value]
  (try (some? (validate value)) (catch Exception _ false)))

;; ---------------------------------------------------------------------------
;; Constructors

(defn describe-envelope
  "What an environment is, and the coordinate that names it."
  [description]
  (validate {:spi/version envelope-version
             :spi/kind :spi.environment/describe
             :environment/description description
             :environment/coordinate (environment-coordinate description)}))

(defn available-envelope
  "An environment can be had, and this is the one."
  [coordinate]
  (validate {:spi/version envelope-version
             :spi/kind :spi.environment/availability
             :environment/available? true
             :environment/coordinate coordinate}))

(defn refusal-envelope
  "An environment cannot be had, for a catalogued reason.

  The reason is an authored string.  A refusal that interpolated a host
  path or an exception message would carry host specifics across a
  repository boundary, which is exactly what the description rules forbid
  in the envelopes that succeed."
  [category reason]
  (validate {:spi/version envelope-version
             :spi/kind :spi.environment/availability
             :environment/available? false
             :environment/refusal {:refusal/category category
                                   :refusal/reason reason}}))

(defn run-envelope
  "One run's envelope, from its fields.

  Fields with nil values are dropped before validation, so an absent exit
  and a present-but-nil exit are the same refusal to invent one.  Every
  other rule is validate's."
  [fields]
  (validate
   (assoc (into {} (remove (fn [[_ value]] (nil? value))) fields)
          :spi/version envelope-version
          :spi/kind :spi.execution/run)))

(defn replay-envelope
  "What a replay restored: the recorded invocation index of the run it
  reproduced, and the environment's live invocation count after the
  reconstruction.

  A faithful replay performs nothing, so the count it reports is the count
  that was there before it ran.  The conformance property lives outside
  the envelope -- the caller brackets the replay with the count -- but the
  envelope carries both numbers so the bracket is auditable after the
  fact."
  [invocation-index invocation-count]
  (validate {:spi/version envelope-version
             :spi/kind :spi.execution/replay
             :replay/invocation-index invocation-index
             :replay/invocation-count invocation-count}))

(defn read-envelope
  "Parses canonical EDN text into a validated envelope.

  The inverse of render, for the consuming side of a repository boundary.
  Read strictly (no evaluation, no tagged literals) and validated exactly
  as a constructed envelope would be, because text that arrived over a
  boundary has no better claim to trust than text built here."
  [^String text]
  (validate (edn/read-string {:default (fn [_tag value] (fail! :non-inert
                                              "No tagged literals in envelopes"
                                              {:tagged value}))}
                             text)))
