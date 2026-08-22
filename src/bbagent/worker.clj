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
        /input                          overlay lower layer
           |  overlayfs, upper in VM    copy-on-write, zero copy in
           v
        /work                           writable, dies with the machine
           |
           v
        argv

   Project-owned code may do whatever it likes to /work.  None of it
   reaches the host, because the only host filesystem the machine can see
   is mounted read-only, and the layer that absorbs the writes lives and
   dies inside the machine."
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
   omitted from it by accident."
  {"HOME" "/root"
   "TMPDIR" "/tmp"
   "LANG" "C.UTF-8"
   "PATH" (str guest-tools
               ":/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")})

(def ^:private environment-name #"^[A-Z_][A-Z0-9_]*$")

(defn- fail! [message data]
  (throw (ex-info message (assoc data :bbagent/error :worker-invalid))))

(def ^:private prelude
  ;; Positional parameters carry the working directory and the argv, so no
  ;; part of a caller's command is ever interpolated into shell source.
  (str/join
   "\n"
   ["set -u"
    (str "mkdir -p /storage/upper /storage/work " guest-work " 2>/dev/null ||"
         " { echo \"" prelude-marker "workspace\" >&2; exit " prelude-exit "; }")
    (str "mount -t overlay overlay -o"
         " lowerdir=" guest-input ",upperdir=/storage/upper,workdir=/storage/work"
         " " guest-work " 2>/dev/null ||"
         " { echo \"" prelude-marker "overlay\" >&2; exit " prelude-exit "; }")
    (str "cd \"" guest-work "/$1\" 2>/dev/null ||"
         " { echo \"" prelude-marker "cwd\" >&2; exit " prelude-exit "; }")
    "shift"
    "exec \"$@\""]))

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

(defn- validated-tools [tools]
  (mapv (fn [tool]
          (let [^Path path (Paths/get (str tool) (make-array String 0))]
            (when-not (.isAbsolute path)
              (fail! "Worker tool directory must be an absolute path"
                     {:worker/tool (str tool)}))
            (str (.toRealPath path (make-array LinkOption 0)))))
        (or tools [])))

(defn- machine-argv
  [{:keys [root cwd argv environment tools limits]}]
  (into
   (into
    (into
     (into ["machine" "run"
            ;; Behind bbagent's own deadline; see teardown-grace-ms.
            "--timeout" (str (+ (:worker/timeout-ms limits) teardown-grace-ms) "ms")
            "--cpus" (str (:worker/cpus limits))
            "--mem" (str (:worker/memory-mib limits))
            ;; No --net.  Outbound networking is off unless it is asked for,
            ;; and A3a never asks.
            "-v" (str root ":" guest-input ":ro")]
           (mapcat (fn [tool] ["-v" (str tool ":" guest-tools ":ro")]) tools))
     (mapcat (fn [[k v]] ["-e" (str k "=" v)]) (sort guest-environment)))
    (mapcat (fn [[k v]] ["-e" (str k "=" v)]) (sort environment)))
   (into ["--" "/bin/sh" "-c" prelude "bbagent-worker" cwd] argv)))

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
  [{:keys [project-root argv cwd environment tools limits snapshot events]}]
  (when-not (and (sequential? argv) (seq argv)
                 (every? #(and (string? %) (not (str/blank? %))) argv))
    (fail! "Worker argv must be a non-empty vector of non-blank strings"
           {:worker/argv argv}))
  (let [limits (merge default-limits limits)
        cwd (validated-cwd cwd)
        environment (validated-environment environment)
        tools (validated-tools tools)
        before (snapshot/manifest project-root snapshot)
        root (:snapshot/root before)
        emit (fn [event] (when events (events event)) event)
        request {:root root :cwd cwd :argv (vec argv)
                 :environment environment :tools tools :limits limits}]
    (emit {:event/type :worker/started
           :worker/runtime executable
           :worker/argv (vec argv)
           :worker/cwd cwd
           :worker/limits limits
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
               :worker/limits limits}
        (= :completed status) (assoc :exit (:exit result))
        stable? (assoc :project/input-coordinate
                       (:snapshot/coordinate before))
        (= :worker-failure status)
        (assoc :worker/error (or (:error/message result)
                                 (str/trim (:text stderr))))))))
