(ns bbagent.spi-smolvm
  "The EDN SPI adapter over the SmolVM executor and worker.

  bbagent already has an execution environment: `bbagent.executor` builds
  it, `bbagent.worker` performs it, and bb4t's protocol carries it.  This
  namespace adds no behavior to any of them.  It is a projection seam: it
  takes the inert results those namespaces already produce -- a worker
  describe map, an executor description, a worker execute result -- and
  restates each one as the envelope `bbagent.spi` keeps, with the
  semantics bb4t's `project/run` already settled.

  The ported rules, none of them new:

  - a worker status is one of :completed, :timeout, :worker-failure, and
    an unknown status fails closed rather than passing through;
  - a run whose project was stable carries its input coordinate and its
    exit; a run whose project moved becomes :project-changed, carries no
    input coordinate, and demotes its process outcome;
  - streams keep their true byte counts and truncation flags;
  - a refusal is catalogued and authored rather than a raw failure.

  Everything here is a function of data it was handed.  Nothing here
  drives the machine manager, mounts anything, or knows a host path; the
  one function that builds a live environment (`probe`) only brackets
  `executor/create` so a refusal can be an envelope instead of a throw."
  (:require [bb4t.execution :as execution]
            [bbagent.executor :as executor]
            [bbagent.spi :as spi]))

(def refusal-reasons
  "The authored reason for each catalogued refusal.

  Paraphrases of the executor's own refusal messages, which are authored
  strings themselves.  Kept here rather than passed through, because the
  executor's unreadable-image failure carries an exception message that
  can name a host path, and a refusal envelope has the same inertness
  obligation as a description."
  {:spi.refusal/manager-unavailable
   "No machine manager is available to run project commands"
   :spi.refusal/manager-unmeasured
   (str "The machine manager's isolation behaviour has not been measured; "
        "execution refuses rather than assuming an approved version")
   :spi.refusal/guest-image-unusable
   "No usable guest image archive is configured for project execution"
   :spi.refusal/guest-image-digest-mismatch
   "The guest image archive does not match the digest this host pinned"
   :spi.refusal/project-identity
   (str "This project has no unprivileged identity to run its commands as; "
        "execution refuses rather than running privileged")
   :spi.refusal/unknown
   "The execution environment refused to build"})

(defn- refusal
  [category]
  (spi/refusal-envelope category (get refusal-reasons category)))

(defn categorize
  "Which catalogued refusal an executor failure is.

  Decided from the failure's data, never from its message, because the
  message can carry host specifics and the category cannot.  The executor
  has exactly five refusal points and each leaves a distinct shape of data
  behind; a failure that matches none of them is refused as :unknown
  rather than guessed at.  Speaks only for failures executor/create
  produces -- a foreign failure with an empty data map is genuinely
  indistinguishable from the executor's own image refusals, which carry
  empty maps too."
  [failure]
  (let [ex (ex-data failure)
        data (if (and (map? ex) (contains? ex :error/data))
               (:error/data ex)
               ex)]
    (cond
      (contains? data :executor/error) :spi.refusal/manager-unavailable
      (contains? data :executor/approved) :spi.refusal/manager-unmeasured
      (contains? data :image/expected) :spi.refusal/guest-image-digest-mismatch
      (contains? data :project/uid) :spi.refusal/project-identity
      ;; The executor's image refusals carry an empty data map or an
      ;; :error/message; anything else did not come from this executor.
      (and (map? data)
           (or (empty? data) (contains? data :error/message)))
      :spi.refusal/guest-image-unusable
      :else :spi.refusal/unknown)))

(defn availability-from-failure
  "An executor refusal as an availability envelope.  Never throws."
  [failure]
  (refusal (categorize failure)))

(defn describe-envelope
  "An executor description as a describe envelope.

  The description is opaque inert data to the SPI; bbagent's own
  descriptions name their type, and this adapter refuses one that does
  not, because an environment that cannot say what implements it cannot be
  attributed to."
  [description]
  (when-not (and (map? description) (keyword? (:executor/type description)))
    (throw (ex-info "A bbagent execution description names its type"
                    {:spi/error :description-invalid
                     :executor/type (:executor/type description)})))
  (spi/describe-envelope description))

(defn describe-environment
  "Any execution environment bb4t accepts, described as an envelope.

  Works against the SmolVM environment and against a test stub alike,
  which is deliberate: conformance is stated over the result shapes, and a
  stub that speaks the shape is a conformant stand-in for the substrate."
  [environment]
  (describe-envelope (:description (execution/describe environment))))

(defn environment-reference
  "How a run is attributed to its environment: a coordinate and a type.

  The coordinate is the same one the describe envelope carries -- computed
  here again from the description, so attribution and description cannot
  drift apart."
  [environment]
  (let [description (:description (execution/describe environment))]
    {:environment/coordinate (spi/environment-coordinate description)
     :environment/type (:executor/type description)}))

(defn probe
  "Whether this host can build an execution environment, as an envelope.

  Brackets executor/create so its fail-closed refusals -- no manager, an
  unmeasured manager, no usable guest image, a root-owned project --
  arrive as availability envelopes instead of throws.  Never throws
  itself: the question 'can one be had' has no answer that is an
  exception."
  [executor-options]
  (try
    (let [environment (executor/create executor-options)]
      (spi/available-envelope
       (spi/environment-coordinate
        (:description (execution/describe environment)))))
    (catch Throwable failure
      (availability-from-failure failure))))

(defn- stream
  [result name]
  {:stream/text (str (get result (keyword name)))
   :stream/bytes (get result (keyword name "bytes"))
   :stream/truncated? (boolean (get result (keyword name "truncated?")))})

(defn run-envelope
  "One worker result as a run envelope.

  The port of bb4t's execution-result semantics to the envelope: stable
  input keeps its status, exit and input coordinate; moved input becomes
  :project-changed, drops the coordinate, and demotes the process outcome;
  an exit survives only when the workload actually exited.  The worker's
  own status vocabulary is enforced, so an environment that invented a
  status fails here the same way it fails in the kernel.

  invocation-index is which execution of this environment the result is:
  for a live environment, executor/invocation-count read after -execute
  returns is exactly that, because -execute increments before it runs.
  There is deliberately no default -- an index a caller did not supply is
  an attribution a caller did not earn."
  [result environment-reference invocation-index]
  (let [status (:status result)]
    (when-not (contains? spi/worker-statuses status)
      (throw (ex-info "Worker result has an unknown status"
                      {:spi/error :run-invalid :status status})))
    (let [stable? (true? (:project/input-stable? result))
          exit (when (contains? result :exit) (:exit result))]
      (spi/run-envelope
       {:run/invocation-index invocation-index
        :run/attribution environment-reference
        :run/input (if stable?
                     {:input/coordinate (:project/input-coordinate result)}
                     {:input/stability :input/project-changed})
        :output/status (if stable? status :project-changed)
        :output/exit (when (and stable? (some? exit)) exit)
        :output/process (when-not stable?
                          (cond-> {:process/status status}
                            (some? exit) (assoc :process/exit exit)))
        :output/stdout (stream result "stdout")
        :output/stderr (stream result "stderr")
        :output/duration-ms (:duration-ms result)
        :run/disposition (:worker/disposition result)
        :output/error (when (= :worker-failure status)
                        (some-> (:worker/error result) str not-empty))}))))

(defn envelope-for
  "The run envelope for a result an environment just produced.

  Attribution comes from the environment's own description and the
  invocation index from its own counter, so a caller cannot attribute a
  run to an environment it did not come from.  The index is the count read
  after the run returns -- the single-flight assumption the invocation
  counter has always carried."
  [environment result]
  (run-envelope result
                (environment-reference environment)
                (executor/invocation-count environment)))

(defn replay-envelope
  "What a replay restored, from the environment that witnessed it.

  The recorded run envelope names the invocation index that produced it;
  this reads the environment's live counter beside it.  A faithful replay
  leaves the counter where it was, so the caller brackets the replay with
  the count and the envelope keeps both numbers honest after the fact."
  [environment recorded-run-envelope]
  (let [recorded (spi/validate recorded-run-envelope)]
    (when-not (= :spi.execution/run (:spi/kind recorded))
      (throw (ex-info "A replay envelope comes from a recorded run"
                      {:spi/error :envelope-invalid
                       :spi/kind (:spi/kind recorded)})))
    (spi/replay-envelope (:run/invocation-index recorded)
                         (executor/invocation-count environment))))
