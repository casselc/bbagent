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
            [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
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

(defn- guest-image!
  "The guest image archive, and the digest of the bytes that will run.

   Host-selected, like everything else a run is bounded by.  There is no
   model-facing argument that names an image, and there is no longer one
   that names a tool directory either: the toolchain is inside this archive,
   so the project is the only host path the machine ever sees.

   An expected digest may be pinned, in which case a different image fails
   here rather than running and being noticed afterwards."
  [image expected-digest]
  (when-not image
    (fail! "Project execution requires a guest image archive" {}))
  (let [^Path path
        (try
          (.toRealPath (Paths/get (str image) (make-array String 0))
                       (make-array LinkOption 0))
          (catch Exception failure
            (fail! "The guest image archive is not readable"
                   {:error/message (.getMessage failure)})))
        _ (when-not (Files/isRegularFile path (make-array LinkOption 0))
            (fail! "The guest image archive must be a regular file" {}))
        digest (str "sha256:" (coordinates/sha-256-path path))]
    (when (and expected-digest (not= expected-digest digest))
      (fail! (str "The guest image archive does not match the digest this "
                  "host pinned; execution refuses rather than running an "
                  "image nobody approved")
             {:image/expected expected-digest :image/actual digest}))
    {:path (str path)
     :digest digest
     :bytes (Files/size path)}))

(def ^:private root-uid 0)

(defn- project-identity!
  "Who a run against this project executes as.

   Derived from the project rather than chosen.  The workspace is an overlay
   whose lower layer is the project itself, so its permissions are the
   project's permissions: a workload whose uid does not match cannot write
   to files it is supposed to own, and one that runs as root can undo the
   parts of the workspace this design relies on.

   A root-owned project has no non-root identity to derive, so it is refused
   rather than quietly run privileged."
  [project-root allow-privileged?]
  (let [^Path path (.toRealPath (Paths/get (str project-root)
                                           (make-array String 0))
                                (make-array LinkOption 0))
        uid (Files/getAttribute path "unix:uid" (make-array LinkOption 0))
        gid (Files/getAttribute path "unix:gid" (make-array LinkOption 0))]
    (when (and (= root-uid uid) (not allow-privileged?))
      (fail! (str "This project is owned by root, so there is no unprivileged "
                  "identity to run its commands as; execution refuses rather "
                  "than running them with full privileges")
             {:project/uid uid}))
    {:uid uid :gid gid}))

(defrecord SmolvmExecutionEnvironment [description invocations configuration]
  execution/ExecutionEnvironment
  (-describe [_] description)
  (-execute [_ {:keys [argv cwd timeout-ms stdout-max-bytes stderr-max-bytes]
                :as request}]
    (swap! invocations inc)
    (let [{:keys [ceilings image exclusions allow-privileged?]} configuration]
      (worker/execute!
       {:project-root (:project/root request)
        :argv argv
        :cwd cwd
        :image (:path image)
        ;; Derived per run from the project bb4t named, so the identity is
        ;; always the one that matches the tree actually being executed
        ;; against rather than one settled when the session started.
        :identity (project-identity! (:project/root request) allow-privileged?)
        ;; The model asked for a deadline within its Context's limit; the
        ;; host still has the last word on it, and on everything else.
        :limits (assoc ceilings
                       :worker/timeout-ms (min timeout-ms
                                               (:worker/timeout-ms ceilings))
                       :worker/stdout-max-bytes
                       (min stdout-max-bytes (:worker/stdout-max-bytes ceilings))
                       :worker/stderr-max-bytes
                       (min stderr-max-bytes (:worker/stderr-max-bytes ceilings)))
        :snapshot {:exclusions exclusions}}))))

(defn project-identity
  "The unprivileged identity a run against this project executes as.

   Public because the evidence phases drive the worker directly and must use
   the same derivation the executor does; deriving it twice by different
   rules would prove something about the phases rather than the product."
  ([project-root] (project-identity project-root false))
  ([project-root allow-privileged?]
   (project-identity! project-root allow-privileged?)))

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
   -- which manager, which guest, which ceilings, whether there is a network
   -- is settled here and published as an inert description.  The Context
   that gets built on it carries that description's coordinate, so a session
   recorded against one execution environment cannot be silently resumed
   against another.

   A project root may be supplied as host policy so a session that could
   never run anything fails when it is created rather than ten turns later."
  ([] (create nil))
  ([{:keys [image image-digest ceilings exclusions project-root
            allow-privileged?]
     :as options}]
   (let [{:keys [version approval]} (approved-manager! options)
         image (guest-image! image image-digest)
         ceilings (merge default-ceilings ceilings)
         exclusions (or exclusions default-exclusions)
         ;; Preflighted, not stored.  Each run derives the identity again
         ;; from the project bb4t names, so this is an early refusal and
         ;; never a second opinion about which project is being run against.
         _ (when project-root
             (project-identity! project-root allow-privileged?))
         description
         {:executor/type :bbagent/smolvm-worker
          :executor/manager worker/executable
          :executor/version version
          :executor/approval approval
          :executor/guest {:image :bbagent/worker-image
                           :image/digest (:digest image)
                           :privilege (if allow-privileged?
                                        :project-owner-or-root
                                        :unprivileged)
                           :identity :derived-from-project-owner
                           :capabilities :none
                           :prelude :in-image
                           :prelude/contract worker/prelude-contract
                           :environment :constructed
                           :host-environment :not-inherited}
          :executor/network :none
          :executor/workspace {:model :overlayfs
                               :project-mount :read-only
                               :host-paths-mounted 1
                               :lifecycle :ephemeral-machine-per-execution
                               :excluded-paths :hidden-from-workload}
          :executor/exclusions (vec (sort exclusions))
          :executor/tools {:bundle :babashka/static
                           :location :in-image
                           :host-directory-mounted? false}
          :executor/ceilings (into (sorted-map) ceilings)}]
     (->SmolvmExecutionEnvironment
      description
      (atom 0)
      {:ceilings ceilings
       :image image
       :exclusions exclusions
       :allow-privileged? (boolean allow-privileged?)}))))

(defn describe
  "The environment's inert description and the coordinate bb4t computes."
  [environment]
  (execution/describe environment))
