(ns bbagent.worker-test
  "A3a: what project-owned code can and cannot do.

   These tests boot a real virtual machine.  They are slow by the standards
   of the rest of the suite and that is the point: an isolation boundary
   asserted against a stub proves the stub."
  (:require [bbagent.process :as process]
            [bbagent.snapshot :as snapshot]
            [bbagent.worker :as worker]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private available?
  (delay (:worker/available? (worker/describe))))

(defn- require-worker! []
  (when-not @available?
    (throw (ex-info (str "A3a evidence requires the " worker/executable
                         " machine manager on this host")
                    {:worker/describe (worker/describe)}))))

(use-fixtures :once (fn [tests] (require-worker!) (tests)))

(defn- temp-dir [label]
  (str (Files/createTempDirectory (str "bbagent-worker-" label)
                                  (make-array FileAttribute 0))))

(defn- project! [label files]
  (let [root (temp-dir label)]
    (doseq [[relative content] files]
      (let [file (io/file root relative)]
        (io/make-parents file)
        (spit file content)))
    root))

(defn- run!! [root argv & [options]]
  (worker/execute! (merge {:project-root root
                           :argv argv
                           :limits {:worker/timeout-ms 60000}}
                          options)))

(defn- shell [script] ["/bin/sh" "-c" script])

(defn- machines-running? []
  (let [result (process/execute! {:argv [worker/executable "machine" "ls"]
                                  :timeout-ms 20000
                                  :inherit-environment? true})]
    (boolean (re-find #"running" (str (:stdout result))))))

;; ---------------------------------------------------------------- functional

(deftest a-command-reports-its-own-exit-status-test
  (let [root (project! "exit" {"README.md" "fixture"})]
    (testing "success"
      (let [result (run!! root (shell "exit 0"))]
        (is (= :completed (:status result)))
        (is (= 0 (:exit result)))))
    (testing "failure keeps the exact status, not a normalized one"
      (let [result (run!! root (shell "exit 42"))]
        (is (= :completed (:status result)))
        (is (= 42 (:exit result)))))))

(deftest streams-are-captured-and-kept-apart-test
  (let [root (project! "streams" {"README.md" "fixture"})
        result (run!! root (shell "echo to-stdout; echo to-stderr >&2"))]
    (is (= :completed (:status result)))
    (is (str/includes? (:stdout result) "to-stdout"))
    (is (not (str/includes? (:stdout result) "to-stderr")))
    (is (str/includes? (:stderr result) "to-stderr"))
    (is (pos? (:duration-ms result)))))

(deftest a-workload-sees-the-project-including-uncommitted-work-test
  (let [root (project! "input" {"src/core.clj" "(ns core)"
                                ".git/HEAD" "ref: refs/heads/main"})]
    (testing "committed content is visible"
      (is (str/includes? (:stdout (run!! root (shell "cat src/core.clj")))
                         "(ns core)")))
    (testing "an edit git has never seen is visible too"
      (spit (io/file root "src/core.clj") "(ns core) ;; uncommitted")
      (is (str/includes? (:stdout (run!! root (shell "cat src/core.clj")))
                         "uncommitted")))))

(deftest a-relative-working-directory-is-honoured-test
  (let [root (project! "cwd" {"sub/marker.txt" "in-sub"})
        result (run!! root (shell "pwd; cat marker.txt") {:cwd "sub"})]
    (is (= :completed (:status result)))
    (is (str/includes? (:stdout result) "in-sub"))
    (is (str/includes? (:stdout result) "/work/sub"))))

(deftest a-working-directory-cannot-leave-the-project-test
  (let [root (project! "cwd-escape" {"README.md" "fixture"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must stay inside the project root"
                          (run!! root (shell "pwd") {:cwd "../.."})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be relative to the project root"
                          (run!! root (shell "pwd") {:cwd "/etc"})))))

;; ----------------------------------------------------------------- isolation

(deftest a-workload-cannot-change-the-authoritative-project-test
  (let [root (project! "immutable" {"src/a.txt" "original"
                                    "src/b.txt" "keep me"})
        result (run!! root (shell (str "echo overwritten > src/a.txt; "
                                       "rm src/b.txt; "
                                       "echo generated > src/c.txt; "
                                       "ls src")))]
    (testing "the workload believes it succeeded"
      (is (= :completed (:status result)))
      (is (= 0 (:exit result)))
      (is (str/includes? (:stdout result) "c.txt"))
      (is (not (str/includes? (:stdout result) "b.txt"))))
    (testing "and the host project is untouched"
      (is (= "original" (slurp (io/file root "src/a.txt"))))
      (is (.exists (io/file root "src/b.txt")))
      (is (not (.exists (io/file root "src/c.txt")))))))

(deftest a-workload-cannot-read-host-files-outside-the-project-test
  (let [outside (temp-dir "outside")
        sentinel (io/file outside "host-secret.txt")
        _ (spit sentinel "SENTINEL-OUTSIDE-THE-PROJECT")
        root (project! "confined" {"README.md" "fixture"})]
    (testing "the sentinel really is readable by the host"
      (is (= "SENTINEL-OUTSIDE-THE-PROJECT" (slurp sentinel))))
    (let [result (run!! root (shell (str "cat " (str sentinel) " 2>&1; "
                                         "echo rc=$?; "
                                         "ls " outside " 2>&1")))]
      (testing "and unavailable to the workload, rather than merely unread"
        (is (not (str/includes? (:stdout result) "SENTINEL")))
        (is (str/includes? (:stdout result) "rc=1"))
        (is (re-find #"No such file|can't open" (:stdout result)))))))

(deftest a-workload-cannot-read-a-secret-in-the-hosts-environment-test
  ;; The machine manager forwards no host environment, so this asserts a
  ;; property of the boundary rather than of a removal list.  The probe
  ;; drives the manager directly so the sentinel can be placed in the
  ;; environment of the very process that launches it.
  (let [root (project! "secret" {"README.md" "fixture"})
        result (process/execute!
                {:argv [worker/executable "machine" "run"
                        "-v" (str root ":/input:ro")
                        "--" "/bin/sh" "-c" "env"]
                 :timeout-ms 60000
                 :environment {"PATH" (System/getenv "PATH")
                               "HOME" (System/getenv "HOME")
                               "BBAGENT_HOST_SENTINEL" "SENTINEL-HOST-CREDENTIAL"
                               "OPENAI_API_KEY" "sk-sentinel-must-not-cross"}})]
    (is (= :exited (:status result)))
    (is (not (str/includes? (:stdout result) "SENTINEL-HOST-CREDENTIAL")))
    (is (not (str/includes? (:stdout result) "sk-sentinel-must-not-cross")))
    (is (not (str/includes? (:stdout result) "BBAGENT_HOST_SENTINEL")))))

(deftest a-workload-receives-only-the-constructed-environment-test
  (let [root (project! "env" {"README.md" "fixture"})
        result (run!! root (shell "env | sort")
                      {:environment {"BBAGENT_DECLARED" "declared-value"}})
        names (into #{} (comp (map #(first (str/split % #"=" 2)))
                              (remove str/blank?))
                    (str/split-lines (:stdout result)))]
    (is (= :completed (:status result)))
    (testing "what the caller declared arrives"
      (is (str/includes? (:stdout result) "BBAGENT_DECLARED=declared-value")))
    (testing "what bbagent constructs arrives"
      (is (every? names (keys worker/guest-environment))))
    (testing "nothing resembling a credential does"
      (is (not-any? #(re-find #"(?i)key|token|secret|password|auth" %) names)))))

(deftest a-declared-environment-cannot-overwrite-the-constructed-one-test
  (let [root (project! "env-clash" {"README.md" "fixture"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must not redefine the constructed environment"
                          (run!! root (shell "true") {:environment {"PATH" "/evil"}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be uppercase identifiers"
                          (run!! root (shell "true")
                                 {:environment {"lower case" "x"}})))))

(deftest a-workload-has-no-network-test
  (let [root (project! "network" {"README.md" "fixture"})
        result (run!! root (shell (str "ip route | grep -c default; "
                                       "wget -T 3 -q -O- http://1.1.1.1 2>&1; "
                                       "echo wget-rc=$?")))]
    (is (= :completed (:status result)))
    (testing "there is no route off the machine"
      (is (str/starts-with? (str/trim (:stdout result)) "0")))
    (testing "and an outbound attempt fails rather than hanging"
      (is (str/includes? (:stdout result) "wget-rc=1"))
      (is (re-find #"unreachable|bad address|download timed out"
                   (str (:stdout result) (:stderr result)))))))

;; ----------------------------------------------------------------- lifecycle

(deftest a-deadline-is-not-a-program-that-chose-a-status-test
  (let [root (project! "timeout" {"README.md" "fixture"})
        result (run!! root (shell "sleep 120") {:limits {:worker/timeout-ms 4000}})]
    (is (= :timeout (:status result)))
    (testing "no exit status is invented for a workload that never exited"
      (is (not (contains? result :exit))))
    (is (= :terminated (:worker/disposition result)))
    (is (>= (:duration-ms result) 4000))
    (is (< (:duration-ms result) 30000))))

(deftest a-timeout-is-distinguishable-from-the-same-numeric-exit-test
  ;; The manager reports its own deadline as 124, the conventional status
  ;; for a timeout.  A program is free to exit 124 for its own reasons, so
  ;; the classification cannot come from the number.
  (let [root (project! "timeout-124" {"README.md" "fixture"})
        chose (run!! root (shell "exit 124"))
        deadline (run!! root (shell "sleep 120")
                        {:limits {:worker/timeout-ms 4000}})]
    (is (= :completed (:status chose)))
    (is (= 124 (:exit chose)))
    (is (= :timeout (:status deadline)))
    (is (not= (:status chose) (:status deadline)))))

(deftest a-timed-out-machine-leaves-nothing-running-test
  (let [root (project! "reaped" {"README.md" "fixture"})]
    (is (= :timeout (:status (run!! root (shell "sleep 300")
                                    {:limits {:worker/timeout-ms 4000}}))))
    (testing "the machine is gone, so nothing inside it can still be running"
      (is (not (machines-running?))))))

(deftest a-process-tree-is-reaped-not-merely-abandoned-test
  ;; The direct proof, at the primitive that provides the guarantee.  The
  ;; workload writes into a host directory every fifth of a second; if the
  ;; machine outlived the host's deadline the file would keep growing.
  ;; Killing only the named process is measurably not enough here, which is
  ;; why bbagent.process destroys the descendants first.
  (let [beat (temp-dir "heartbeat")
        tick (io/file beat "tick")
        result (process/execute!
                {:argv [worker/executable "machine" "run"
                        "-v" (str beat ":/beat")
                        "--" "/bin/sh" "-c"
                        (str "while true; do echo t >> /beat/tick; "
                             "sleep 0.2; done & sleep 300")]
                 :timeout-ms 5000
                 :inherit-environment? true})
        at-deadline (count (line-seq (io/reader tick)))]
    (is (= :timeout (:status result)))
    (is (pos? at-deadline) "the workload was genuinely running")
    (Thread/sleep 4000)
    (is (= at-deadline (count (line-seq (io/reader tick))))
        "a backgrounded process inside the machine stopped when the machine did")
    (is (not (machines-running?)))))

(deftest every-execution-gets-a-machine-that-never-ran-anything-test
  (let [root (project! "fresh" {"README.md" "fixture"})]
    (is (= :completed (:status (run!! root (shell "echo left-behind > /work/residue.txt")))))
    (let [result (run!! root (shell "ls /work/residue.txt 2>&1; echo rc=$?"))]
      (testing "the previous execution's writes are not in this one"
        (is (str/includes? (:stdout result) "rc=1"))
        (is (not (.exists (io/file root "residue.txt"))))))))

;; -------------------------------------------------------------------- bounds

(deftest oversized-output-is-truncated-and-says-so-test
  (let [root (project! "bounds" {"README.md" "fixture"})
        result (run!! root (shell "yes abcdefghij | head -n 20000; yes ABCDEFGHIJ | head -n 20000 >&2")
                      {:limits {:worker/stdout-max-bytes 4096
                                :worker/stderr-max-bytes 4096}})]
    (is (= :completed (:status result)))
    (testing "what was kept is bounded"
      (is (<= (count (.getBytes ^String (:stdout result) "UTF-8")) 4096))
      (is (<= (count (.getBytes ^String (:stderr result) "UTF-8")) 4096)))
    (testing "and the true size is reported rather than the kept size"
      (is (true? (:stdout/truncated? result)))
      (is (true? (:stderr/truncated? result)))
      (is (= 220000 (:stdout/bytes result)))
      (is (= 220000 (:stderr/bytes result))))))

(deftest output-within-the-bound-is-not-marked-truncated-test
  (let [root (project! "untruncated" {"README.md" "fixture"})
        result (run!! root (shell "echo small"))]
    (is (false? (:stdout/truncated? result)))
    (is (false? (:stderr/truncated? result)))
    (is (= 6 (:stdout/bytes result)))))

(deftest a-result-names-the-project-state-it-ran-against-test
  (let [root (project! "coordinate" {"src/a.txt" "original"})
        first-run (run!! root (shell "cat src/a.txt"))]
    (is (true? (:project/input-stable? first-run)))
    (is (= (snapshot/coordinate root) (:project/input-coordinate first-run)))
    (testing "the same project state yields the same coordinate"
      (is (= (:project/input-coordinate first-run)
             (:project/input-coordinate (run!! root (shell "true"))))))
    (testing "a changed project is a different coordinate"
      (spit (io/file root "src/a.txt") "edited")
      (let [second-run (run!! root (shell "cat src/a.txt"))]
        (is (not= (:project/input-coordinate first-run)
                  (:project/input-coordinate second-run)))
        (is (str/includes? (:stdout second-run) "edited"))))))

(deftest a-project-that-moves-under-a-run-claims-no-coordinate-test
  ;; The overlay's lower layer is the live host tree, not a frozen copy, so
  ;; a concurrent edit is visible to the workload.  A coordinate naming a
  ;; state the run did not entirely see would be worse than none.
  (let [root (project! "unstable" {"src/a.txt" "original"})
        mutate (future (Thread/sleep 1500)
                       (spit (io/file root "src/a.txt") "changed mid-run"))
        result (run!! root (shell "sleep 4; cat src/a.txt"))]
    @mutate
    (is (= :completed (:status result)))
    (is (false? (:project/input-stable? result)))
    (is (not (contains? result :project/input-coordinate)))))

(deftest an-unrepresentable-symlink-stops-the-execution-test
  (let [root (project! "symlink" {"src/a.txt" "content"})]
    (Files/createSymbolicLink
     (.toPath (io/file root "escape"))
     (.toPath (io/file "/etc/passwd"))
     (make-array FileAttribute 0))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"absolute symbolic link"
                          (run!! root (shell "true"))))))

(deftest an-execution-reports-what-it-did-without-reporting-secrets-test
  (let [root (project! "events" {"README.md" "fixture"})
        recorded (atom [])
        result (run!! root (shell "exit 0")
                      {:events #(swap! recorded conj %)
                       :environment {"BBAGENT_DECLARED" "not-for-the-record"}})
        [started finished] @recorded]
    (is (= 2 (count @recorded)))
    (testing "what ran, against which project state"
      (is (= :worker/started (:event/type started)))
      (is (= ["/bin/sh" "-c" "exit 0"] (:worker/argv started)))
      (is (= "." (:worker/cwd started)))
      (is (= (:project/input-coordinate result)
             (:project/input-coordinate started))))
    (testing "how it ended"
      (is (= :worker/finished (:event/type finished)))
      (is (= :completed (:worker/status finished)))
      (is (= :terminated (:worker/disposition finished)))
      (is (= 0 (:exit finished)))
      (is (pos? (:duration-ms finished))))
    (testing "and nothing about the environment it was given"
      (is (not (str/includes? (pr-str @recorded) "not-for-the-record")))
      (is (not (str/includes? (pr-str @recorded) "BBAGENT_DECLARED"))))))

(deftest a-command-that-does-not-exist-is-the-workloads-own-failure-test
  ;; A typo in argv is a program that could not be found, which the shell
  ;; reports as 127.  It is not a worker failure and must not be dressed up
  ;; as one: the machine did its job.
  (let [root (project! "missing-command" {"README.md" "fixture"})
        result (run!! root ["definitely-not-a-real-command"])]
    (is (= :completed (:status result)))
    (is (= 127 (:exit result)))))

(deftest a-workspace-that-could-not-be-entered-is-a-worker-failure-test
  ;; The inverse: the workload never ran, so there is no exit status of its
  ;; own to report and none is invented.
  (let [root (project! "missing-cwd" {"README.md" "fixture"})
        result (run!! root (shell "echo should-not-run") {:cwd "no/such/dir"})]
    (is (= :worker-failure (:status result)))
    (is (not (contains? result :exit)))
    (is (= "" (:stdout result)) "the workload never ran, so it wrote nothing")
    (is (str/includes? (:worker/error result) "prelude failed"))))

(deftest an-invalid-request-is-refused-before-a-machine-starts-test
  (let [root (project! "invalid" {"README.md" "fixture"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-empty vector of non-blank strings"
                          (run!! root [])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"non-empty vector of non-blank strings"
                          (run!! root ["  "])))))
