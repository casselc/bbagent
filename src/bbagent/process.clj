(ns bbagent.process
  "Bounded host subprocess execution.

   Trusted host code only.  Nothing here is projected into a model-facing
   Context, and nothing here knows what it is running: it takes an argv, a
   deadline and two byte budgets, and returns inert data.

   Two callers need the same three properties and previously had none of
   them: a deadline, an output bound, and a process that is actually dead
   when the call returns.  The project coordinate shells out to git, and the
   A3a worker shells out to a virtual machine manager."
  (:require [clojure.string :as str])
  (:import [java.io ByteArrayOutputStream InputStream]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(def default-stream-max-bytes
  "How much of one stream is captured before the rest is counted and dropped.

   A build that prints a megabyte of warnings should still report its exit
   code, so exceeding this is not an error.  It is recorded, because a
   truncated stream that does not say so is a lie about what ran."
  1048576)

(def max-timeout-ms
  "The longest deadline a caller may ask for.

   A deadline is the only thing standing between a wedged child and a host
   process that waits forever, so there is an upper bound on how long a
   caller may decline to be protected."
  3600000)

(defn- drain
  "Reads a stream to exhaustion, keeping at most max-bytes of it.

   Reading continues past the budget rather than stopping at it.  A child
   whose output is not consumed blocks on a full pipe, so abandoning the
   stream early would turn a chatty command into a hang."
  [^InputStream stream max-bytes]
  (let [buffer (byte-array 8192)
        captured (ByteArrayOutputStream.)]
    (loop [total 0]
      (let [read (.read stream buffer)]
        (if (neg? read)
          (let [text (String. (.toByteArray captured) StandardCharsets/UTF_8)]
            {:text text
             :bytes total
             :truncated? (> total max-bytes)})
          (do
            (when (< (.size captured) max-bytes)
              (.write captured buffer 0
                      (min read (- max-bytes (.size captured)))))
            (recur (+ total read))))))))

(defn- reap!
  "Destroys a process and everything it started.

   Destroying only the named process is not enough, and this is not a
   theoretical concern: a process that supervises work in another address
   space leaves that work running when its supervisor is killed, so the
   host stops waiting while the workload continues.  Measured against the
   A3a virtual machine manager, killing the front end alone left the VM
   running and burning CPU.

   Descendants are collected before the parent is destroyed.  Once the
   parent dies its children are reparented and no longer answer to it, so
   the reverse order silently reaps nothing."
  [^Process process]
  (let [descendants (vec (.toArray (.descendants (.toHandle process))))]
    (doseq [^java.lang.ProcessHandle handle descendants]
      (.destroyForcibly handle))
    (.destroyForcibly process)
    (count descendants)))

(defn- valid-argv? [argv]
  (and (sequential? argv)
       (seq argv)
       (every? #(and (string? %) (not (str/blank? %))) argv)))

(defn execute!
  "Runs argv to completion, to its deadline, or to a failure to start.

   Returns {:status :exited|:timeout|:start-failure}.  :exit is present only
   when the process actually exited, so a timeout cannot be mistaken for a
   program that chose an exit code.  On timeout the process is destroyed
   forcibly before the call returns."
  [{:keys [argv timeout-ms directory environment inherit-environment?
           stdout-max-bytes stderr-max-bytes]
    :or {stdout-max-bytes default-stream-max-bytes
         stderr-max-bytes default-stream-max-bytes}}]
  (when-not (valid-argv? argv)
    (throw (ex-info "Process argv must be a non-empty vector of non-blank strings"
                    {:bbagent/error :process-invalid})))
  (when-not (and (integer? timeout-ms) (pos? timeout-ms)
                 (<= timeout-ms max-timeout-ms))
    (throw (ex-info "Process timeout must be a positive number of milliseconds within the bound"
                    {:bbagent/error :process-invalid
                     :timeout/requested timeout-ms
                     :timeout/max max-timeout-ms})))
  (let [builder (ProcessBuilder. ^java.util.List (vec argv))
        started (System/nanoTime)]
    (when directory
      (.directory builder (java.io.File. (str directory))))
    ;; The child's environment is constructed, not inherited, unless a caller
    ;; explicitly asks otherwise.  git needs the ambient environment to find
    ;; its own configuration; a worker must not receive it.
    (when-not inherit-environment?
      (.clear (.environment builder)))
    (doseq [[k v] environment]
      (.put (.environment builder) (str k) (str v)))
    (let [elapsed #(quot (- (System/nanoTime) started) 1000000)]
      (try
        (let [process (.start builder)
              stdout (future (drain (.getInputStream process) stdout-max-bytes))
              stderr (future (drain (.getErrorStream process) stderr-max-bytes))
              exited? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)]
          (when-not exited?
            (reap! process)
            (.waitFor process 5000 TimeUnit/MILLISECONDS))
          ;; The drains end when the pipes close, which destroying the process
          ;; causes.  They are still dereferenced with a bound: a grandchild
          ;; holding the pipe open must not become this process's problem.
          (let [empty-stream {:text "" :bytes 0 :truncated? false}
                out (deref stdout 5000 empty-stream)
                err (deref stderr 5000 empty-stream)]
            (cond-> {:status (if exited? :exited :timeout)
                     :duration-ms (elapsed)
                     :stdout (:text out)
                     :stdout/bytes (:bytes out)
                     :stdout/truncated? (:truncated? out)
                     :stderr (:text err)
                     :stderr/bytes (:bytes err)
                     :stderr/truncated? (:truncated? err)}
              exited? (assoc :exit (.exitValue process)))))
        (catch java.io.IOException failure
          {:status :start-failure
           :duration-ms (elapsed)
           :error/message (.getMessage failure)})))))
