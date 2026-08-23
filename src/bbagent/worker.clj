(ns bbagent.worker
  "Isolated execution of project-owned code.

   Trusted host code.  Nothing here is reachable from a model-facing
   Context, and A3a adds no semantic operation that would make it so: this
   is the substrate a later execution capability would sit on, proven
   before any model is given authority over it.

   The shape of the boundary:

     authoritative project root
           |  read-only mount           the host tree is never writable
           v
        /input                          overlay lower layer, then masked
           |  overlayfs, upper in VM    copy-on-write, zero copy in
           v
        /work                           writable, dies with the machine
           |  excluded paths whited out
           |  privilege dropped here    no capabilities past this line
           v
        argv

   Project-owned code may do whatever it likes to /work.  None of it
   reaches the host, because the only host filesystem the machine can see
   is mounted read-only, and the layer that absorbs the writes lives and
   dies inside the machine.

   What the workload sees is what the input coordinate describes.  Paths
   the snapshot excluded are removed from the workspace and the raw export
   is covered, and the workload then runs with no capabilities at all, so
   it cannot uncover them.  That is a property of the guest now rather than
   of the workload's good manners; the machine is still the boundary the
   isolation rests on, and this is defence in depth behind it.

   The guest side of all of that lives in the image, not here.  This
   namespace hands smolvm an image and some data -- what to hide, who to
   run as, where to start, what to run -- and the image digest covers the
   rest."
  (:require [bbagent.process :as process]
            [bbagent.snapshot :as snapshot]
            [clojure.string :as str])
  (:import [java.nio.file LinkOption Path Paths]))

(def executable
  "The virtual machine manager this worker drives.

   Named once.  A future semantic execution capability must not know that
   this string exists, and neither should anything else in bbagent."
  "smolvm")

(def guest-input "/input")
(def guest-work "/work")
(def guest-tools "/opt/bbagent-tools")

(def guest-prelude
  "Where the image keeps its half of the boundary."
  "/usr/local/bin/bbagent-prelude")

(def prelude-contract
  "The argument order the host and the image have to agree on.

   Checked by the prelude before it mounts anything.  A binary and an image
   that disagreed would otherwise build a workspace and then run the wrong
   thing inside it, which is a worse failure than not starting."
  "1")

(def prelude-exit
  "The exit status the guest prelude uses when it never reached the argv.

   A command that could not be started did not fail; it did not run.  The
   status is paired with a marker on stderr so the host does not have to
   decide what a bare 125 from a project's own program meant."
  125)

(def prelude-marker "bbagent-worker: prelude failed: ")

(def default-limits
  {:worker/cpus 2
   :worker/memory-mib 2048
   :worker/timeout-ms 120000
   :worker/stdout-max-bytes process/default-stream-max-bytes
   :worker/stderr-max-bytes process/default-stream-max-bytes})

(def ^:private teardown-grace-ms
  "How much longer the machine manager waits than bbagent does.

   The host deadline is the one that classifies a timeout, so it has to
   fire first.  The manager's own deadline stays behind it as a backstop
   for the case where bbagent cannot reap the process tree at all."
  5000)

(def guest-environment
  "Exactly what a workload receives.

   This is constructed rather than filtered.  The measured behaviour of the
   machine manager is that it forwards no host environment at all, so
   nothing is being removed here and nothing needs to be: whatever is in
   this map is the whole environment, and a host credential cannot be
   omitted from it by accident.

   HOME is absent deliberately.  The workload does not run as root and has
   no business in root's home, so the prelude points it at a directory it
   owns; naming one here would only be a value the guest overrides."
  {"TMPDIR" "/tmp"
   "LANG" "C.UTF-8"
   "PATH" (str guest-tools
               ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")})

(def ^:private environment-name #"^[A-Z_][A-Z0-9_]*$")

(defn- fail! [message data]
  (throw (ex-info message (assoc data :bbagent/error :worker-invalid))))

(defn- validated-hidden
  "The paths the workspace must not contain.

   These come from the snapshot's own account of what it refused to
   describe, so they are already relative and already inside the tree.  They
   are checked anyway: this is the one list that decides what a workload can
   see, and a path that escaped it would hide something outside the project
   instead of something inside it."
  [hidden]
  (mapv (fn [path]
          (let [path (str path)
                ^Path parsed (Paths/get path (make-array String 0))]
            (when (or (str/blank? path) (.isAbsolute parsed))
              (fail! "Worker hidden path must be relative to the project root"
                     {:worker/hidden path}))
            (let [normalized (str (.normalize parsed))]
              (when (or (str/blank? normalized)
                        (= ".." normalized)
                        (str/starts-with? normalized "../"))
                (fail! "Worker hidden path must stay inside the project root"
                       {:worker/hidden path}))
              normalized)))
        (or hidden [])))

(defn- validated-cwd
  "A caller's working directory, as a path that cannot leave the workspace."
  [cwd]
  (let [cwd (if (str/blank? (str cwd)) "." (str cwd))
        ^Path path (Paths/get cwd (make-array String 0))]
    (when (.isAbsolute path)
      (fail! "Worker cwd must be relative to the project root" {:worker/cwd cwd}))
    (let [normalized (str (.normalize path))]
      (when (or (= ".." normalized) (str/starts-with? normalized "../"))
        (fail! "Worker cwd must stay inside the project root" {:worker/cwd cwd}))
      ;; Normalizing "." yields the empty path, which is the project root
      ;; but is not a name a shell can change directory to.
      (if (str/blank? normalized) "." normalized))))

(defn- validated-environment [environment]
  (reduce-kv
   (fn [accumulated k v]
     (let [k (str k)]
       (when-not (re-matches environment-name k)
         (fail! "Worker environment names must be uppercase identifiers"
                {:worker/environment-name k}))
       (when (contains? guest-environment k)
         (fail! "Worker environment must not redefine the constructed environment"
                {:worker/environment-name k}))
       (assoc accumulated k (str v))))
   {}
   (or environment {})))

(defn- validated-image
  "The guest image archive, as a path smolvm can be handed.

   There is no longer a tool directory to validate.  The toolchain is inside
   the image, so the one host path this worker mounts is the project, and a
   caller cannot name a second one."
  [image]
  (when (str/blank? (str image))
    (fail! "Worker requires a guest image archive" {}))
  (let [^Path path (Paths/get (str image) (make-array String 0))]
    (when-not (.isAbsolute path)
      (fail! "Worker guest image must be an absolute path"
             {:worker/image (str image)}))
    (str (.toRealPath path (make-array LinkOption 0)))))

(defn- validated-identity
  "The uid and gid the workload runs as.

   Not root, and not a host identity the caller picked: the executor derives
   it from the project the run is against, because the overlay's permissions
   are the project's permissions and a workload whose uid does not match
   them cannot write to its own workspace."
  [identity]
  (let [{:keys [uid gid]} identity]
    (when-not (and (integer? uid) (integer? gid) (pos? uid) (not (neg? gid)))
      (fail! "Worker identity must be a non-root uid and a gid"
             {:worker/identity identity}))
    (str uid ":" gid)))

(defn- machine-argv
  "The manager command line: an image, one read-only mount, and data.

   The guest command is the image's own prelude followed by arguments, not
   shell source assembled here.  Nothing a caller supplies is interpolated
   into anything that gets parsed."
  [{:keys [root cwd argv environment image identity contract limits hidden]}]
  (into
   (into
    (into ["machine" "run"
           "--image" image
           ;; Behind bbagent's own deadline; see teardown-grace-ms.
           "--timeout" (str (+ (:worker/timeout-ms limits) teardown-grace-ms) "ms")
           "--cpus" (str (:worker/cpus limits))
           "--mem" (str (:worker/memory-mib limits))
           ;; No --net.  Outbound networking is off unless it is asked for,
           ;; and nothing here asks.  The project is the only host path
           ;; mounted, because the toolchain is in the image.
           "-v" (str root ":" guest-input ":ro")]
          (mapcat (fn [[k v]] ["-e" (str k "=" v)]) (sort guest-environment)))
    (mapcat (fn [[k v]] ["-e" (str k "=" v)]) (sort environment)))
   (-> ["--" guest-prelude contract identity (str (count hidden))]
       (into hidden)
       (conj cwd)
       (into argv))))

(def ^:private manager-banner
  ;; The machine manager announces itself on stderr and has no quiet flag,
  ;; so its progress line would otherwise be reported as something the
  ;; workload wrote.  It is removed, and its bytes are removed from the
  ;; count, so :stderr and :stderr/bytes describe the workload alone.
  #"^Starting ephemeral machine \(vm-[0-9a-f]+\)\.\.\.\R")

(defn- workload-stderr
  [result]
  (let [text (str (:stderr result))]
    (if-let [banner (re-find manager-banner text)]
      {:text (subs text (count banner))
       :bytes (max 0 (- (:stderr/bytes result)
                        (alength (.getBytes ^String banner "UTF-8"))))}
      {:text text :bytes (:stderr/bytes result)})))

(defn- prelude-failure?
  [{:keys [status exit stderr]}]
  (and (= :exited status)
       (= prelude-exit exit)
       (str/includes? (str stderr) prelude-marker)))

(defn describe
  "What machine manager this host has, if any.

   Reported rather than assumed, because the coordinate of the thing that
   provides the isolation belongs in the evidence for the isolation."
  []
  (let [result (process/execute! {:argv [executable "--version"]
                                 :timeout-ms 15000
                                 :inherit-environment? true})]
    (if (and (= :exited (:status result)) (zero? (:exit result)))
      {:worker/runtime executable
       :worker/version (str/trim (:stdout result))
       :worker/available? true}
      {:worker/runtime executable
       :worker/available? false
       :worker/error (or (not-empty (str/trim (str (:stderr result))))
                         (:error/message result)
                         (str "exit " (:exit result)))})))

(defn execute!
  "Runs argv against the project, inside a machine that is destroyed after.

   Returns inert data.  :exit is present only when the workload actually
   exited, so a deadline cannot be read as a program that chose a status,
   and the project input coordinate is present only when the project did
   not change while the workload was running: a coordinate that names a
   tree the run did not entirely see would be worse than no coordinate."
  [{:keys [project-root argv cwd environment image identity limits snapshot
           events contract]}]
  (when-not (and (sequential? argv) (seq argv)
                 (every? #(and (string? %) (not (str/blank? %))) argv))
    (fail! "Worker argv must be a non-empty vector of non-blank strings"
           {:worker/argv argv}))
  (let [limits (merge default-limits limits)
        cwd (validated-cwd cwd)
        environment (validated-environment environment)
        image (validated-image image)
        identity (validated-identity identity)
        ;; Overridable only so the mismatch can be proved rather than
        ;; reasoned about; nothing but an evidence phase ever passes it.
        contract (or contract prelude-contract)
        before (snapshot/manifest project-root snapshot)
        root (:snapshot/root before)
        ;; The workspace hides exactly what the manifest refused to describe.
        ;; Taken from the manifest rather than from the exclusion names, so
        ;; the two cannot drift: what a workload can see is what the
        ;; coordinate covers.
        hidden (validated-hidden (:snapshot/excluded-paths before))
        emit (fn [event] (when events (events event)) event)
        request {:root root :cwd cwd :argv (vec argv) :hidden hidden
                 :environment environment :image image :identity identity
                 :contract contract :limits limits}]
    (emit {:event/type :worker/started
           :worker/runtime executable
           :worker/argv (vec argv)
           :worker/cwd cwd
           :worker/limits limits
           :worker/hidden-paths hidden
           :worker/identity identity
           :project/input-coordinate (:snapshot/coordinate before)})
    (let [result (process/execute!
                  {:argv (into [executable] (machine-argv request))
                   :timeout-ms (:worker/timeout-ms limits)
                   :inherit-environment? true
                   :stdout-max-bytes (:worker/stdout-max-bytes limits)
                   :stderr-max-bytes (:worker/stderr-max-bytes limits)})
          stderr (workload-stderr result)
          after (snapshot/coordinate project-root snapshot)
          stable? (= (:snapshot/coordinate before) after)
          status (cond
                   (= :timeout (:status result)) :timeout
                   (= :start-failure (:status result)) :worker-failure
                   (prelude-failure? result) :worker-failure
                   :else :completed)]
      (emit
       (merge
        {:event/type :worker/finished
         :worker/status status
         ;; Every machine is ephemeral and every machine is destroyed, so
         ;; there is no reuse for a timeout to have to poison.
         :worker/disposition :terminated
         :duration-ms (:duration-ms result)}
        (when (= :completed status) {:exit (:exit result)})))
      (cond-> {:status status
               :worker/disposition :terminated
               :worker/runtime executable
               :duration-ms (:duration-ms result)
               :stdout (:stdout result)
               :stdout/bytes (:stdout/bytes result)
               :stdout/truncated? (:stdout/truncated? result)
               :stderr (:text stderr)
               :stderr/bytes (:bytes stderr)
               :stderr/truncated? (:stderr/truncated? result)
               :project/input-stable? stable?
               :project/entry-count (:snapshot/entry-count before)
               :project/bytes (:snapshot/bytes before)
               :project/hidden-paths hidden
               :worker/identity identity
               :worker/limits limits}
        (= :completed status) (assoc :exit (:exit result))
        stable? (assoc :project/input-coordinate
                       (:snapshot/coordinate before))
        (= :worker-failure status)
        (assoc :worker/error (or (:error/message result)
                                 (str/trim (:text stderr))))))))
