(ns bbagent.executor
  "The authorized execution environment bbagent supplies to bb4t.

   bb4t knows that a Context may be allowed to run the project's own
   commands somewhere that is not this host.  This is the somewhere.  It is
   the only place in the model-facing path that knows a virtual machine is
   involved, and it is trusted host code: everything a run is bounded by --
   which project, which tools, how much memory, whether there is a network --
   is decided here from host configuration and is not an argument anyone
   downstream can supply.

   The model's request reaches `-execute` already reduced to argv, a
   relative directory and a deadline.  Everything else in the worker call
   below comes from this namespace."
  (:require [bb4t.execution :as execution]
            [bbagent.errors :as errors]
            [bbagent.process :as process]
            [bbagent.snapshot :as snapshot]
            [bbagent.worker :as worker]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def approved-versions
  "Machine manager versions whose isolation behaviour has been measured.

   A3a measured one.  Its central finding -- that killing the manager's
   front-end leaves the machine running, and that cleanup means reaping the
   process tree -- is a fact about an implementation, not a guarantee of the
   command line.  A version nobody has measured may be better, worse, or
   differently wrong, and the honest response to not knowing is to refuse."
  #{"1.7.5"})

(def default-ceilings
  "The host-enforced bounds on any run.

   These are not model arguments and there is no operation that makes them
   into ones.  A Context's limits bound what the model may ask for within
   these; they cannot raise them."
  {:worker/cpus 2
   :worker/memory-mib 2048
   :worker/timeout-ms 300000
   :worker/stdout-max-bytes 1048576
   :worker/stderr-max-bytes 1048576})

(def default-exclusions
  "Project paths a run neither describes nor sees.

   `.git` is here because A3b does not give the worker git and because git
   rewrites its own directory during operations that look like reads, which
   would make a project appear to move under a run that changed nothing."
  snapshot/default-exclusions)

(defn- fail! [message data]
  (throw (errors/error :agent-invalid-action message data)))

(defn- parsed-version
  "The version number out of a manager's version banner."
  [reported]
  (some-> reported str str/trim (str/split #"\s+") last str/trim not-empty))

(defn- approved-manager!
  "The manager this host has, if it is one whose behaviour is known.

   Fails closed twice over: once when there is no manager at all, and once
   when there is one whose isolation nobody has measured.  An override is
   available to a trusted host and is recorded in the coordinate, so a run
   made under one is never mistaken for a run made under an approved
   version."
  [{:keys [allow-unapproved-version?]
    approved :approved-versions}]
  ;; The approved set is host configuration like everything else here.  It is
  ;; overridable so that the refusal can be proved against a real manager
  ;; rather than only reasoned about: a check nobody has watched fail is a
  ;; check nobody has tested.
  (let [approved (or approved approved-versions)
        described (worker/describe)]
    (when-not (:worker/available? described)
      (fail! "No machine manager is available to run project commands"
             {:executor/manager worker/executable
              :executor/error (:worker/error described)}))
    (let [version (parsed-version (:worker/version described))
          approved? (contains? approved version)]
      (when-not (or approved? allow-unapproved-version?)
        (fail! (str "The machine manager on this host is version " version
                    ", whose isolation behaviour has not been measured; "
                    "project execution refuses rather than assuming it "
                    "matches an approved version")
               {:executor/manager worker/executable
                :executor/version version
                :executor/approved approved}))
      {:version version
       :approval (if approved? :recognized :host-override)})))

(defn- tool-bundle!
  "The one directory of trusted tools a run may use, and its coordinate.

   Host-selected.  There is no model-facing argument that names a directory
   to mount, because a guessed host path offered as a tool path would be a
   read of the host wearing an execution capability's clothes."
  [directory]
  (when-not directory
    (fail! "Project execution requires a trusted tool bundle" {}))
  (let [^Path path
        (try
          (.toRealPath (Paths/get (str directory) (make-array String 0))
                       (make-array LinkOption 0))
          (catch Exception failure
            (fail! "The trusted tool bundle directory is not readable"
                   {:error/message (.getMessage failure)})))
        _ (when-not (Files/isDirectory path (make-array LinkOption 0))
            (fail! "The trusted tool bundle must be a directory" {}))
        ;; Digested with the project snapshot, which refuses symbolic links
        ;; that cannot be handed over faithfully.  That is the right rule
        ;; here too: the bundle is mounted into the machine, and a link out
        ;; of it would dangle there.
        manifest (try
                   (snapshot/manifest path {:exclusions #{}})
                   (catch Exception failure
                     (fail! (str "The trusted tool bundle cannot be handed to a "
                                 "worker faithfully: " (ex-message failure))
                            {:executor/tool-bundle (str path)})))
        names (into []
                    (comp (filter #(= :file (:kind %))) (map :path))
                    (:snapshot/entries manifest))]
    (when (empty? names)
      (fail! "The trusted tool bundle is empty" {}))
    {:path (str path)
     :contents names
     :coordinate (:snapshot/coordinate manifest)}))

(defn- tool-version
  "What the bundle's babashka calls itself, or nil.

   Reported rather than assumed: the digest says which bytes, and this says
   what those bytes answer to, which is the part a reader of an evidence
   file can recognise."
  [^String directory]
  (let [^Path binary (Paths/get directory (into-array String ["bb"]))]
    (when (Files/isRegularFile binary (make-array LinkOption 0))
      (let [result (process/execute!
                    {:argv [(str binary) "--version"] :timeout-ms 15000})]
        (when (and (= :exited (:status result)) (zero? (:exit result)))
          (not-empty (str/trim (:stdout result))))))))

(defrecord SmolvmExecutionEnvironment [description invocations configuration]
  execution/ExecutionEnvironment
  (-describe [_] description)
  (-execute [_ {:keys [argv cwd timeout-ms stdout-max-bytes stderr-max-bytes]
                :as request}]
    (swap! invocations inc)
    (let [{:keys [ceilings tools exclusions]} configuration]
      (worker/execute!
       {:project-root (:project/root request)
        :argv argv
        :cwd cwd
        ;; The model asked for a deadline within its Context's limit; the
        ;; host still has the last word on it, and on everything else.
        :limits (assoc ceilings
                       :worker/timeout-ms (min timeout-ms
                                               (:worker/timeout-ms ceilings))
                       :worker/stdout-max-bytes
                       (min stdout-max-bytes (:worker/stdout-max-bytes ceilings))
                       :worker/stderr-max-bytes
                       (min stderr-max-bytes (:worker/stderr-max-bytes ceilings)))
        :tools [(:path tools)]
        :snapshot {:exclusions exclusions}}))))

(defn invocation-count
  "How many times this environment has actually run something.

   The number a replay must not move.  A recovery that reconstructed a run
   from its receipt and also performed it would leave the same result and a
   different count, which is the only way to tell those two apart from
   outside."
  [environment]
  @(:invocations environment))

(defn create
  "Builds the execution environment, or refuses to.

   Everything that could make one run mean something different from another
   -- which manager, which tools, which ceilings, whether there is a network
   -- is settled here and published as an inert description.  The Context
   that gets built on it carries that description's coordinate, so a session
   recorded against one execution environment cannot be silently resumed
   against another."
  ([] (create nil))
  ([{:keys [tools ceilings exclusions] :as options}]
   (let [{:keys [version approval]} (approved-manager! options)
         bundle (tool-bundle! tools)
         ceilings (merge default-ceilings ceilings)
         exclusions (or exclusions default-exclusions)
         description
         {:executor/type :bbagent/smolvm-worker
          :executor/manager worker/executable
          :executor/version version
          :executor/approval approval
          :executor/guest {:privilege :root
                           :environment :constructed
                           :host-environment :not-inherited}
          :executor/network :none
          :executor/workspace {:model :overlayfs
                               :project-mount :read-only
                               :lifecycle :ephemeral-machine-per-execution
                               :excluded-paths :hidden-from-workload}
          :executor/exclusions (vec (sort exclusions))
          :executor/tools {:bundle :babashka/static
                           :version (tool-version (:path bundle))
                           :contents (:contents bundle)
                           :coordinate (:coordinate bundle)}
          :executor/ceilings (into (sorted-map) ceilings)}]
     (->SmolvmExecutionEnvironment
      description
      (atom 0)
      {:ceilings ceilings
       :tools bundle
       :exclusions exclusions}))))

(defn describe
  "The environment's inert description and the coordinate bb4t computes."
  [environment]
  (execution/describe environment))
