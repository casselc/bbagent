(ns bbagent.a3a-smoke
  "A3a evidence, from the native product rather than from the JVM.

   The deterministic suite proves these properties too.  This proves them
   about the thing that ships: a native image whose reachability now
   includes a virtual machine manager, driven by trusted host code that no
   model-facing Context can name."
  (:require [bbagent.process :as process]
            [bbagent.snapshot :as snapshot]
            [bbagent.worker :as worker]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- verdict [ok?] (if ok? :ok :failed))

(defn- shell [script] ["/bin/sh" "-c" script])

(defn- run!! [root argv options]
  (worker/execute! (merge {:project-root root :argv argv} options)))

(defn- machines-running? []
  (let [result (process/execute! {:argv [worker/executable "machine" "ls"]
                                  :timeout-ms 20000
                                  :inherit-environment? true})]
    (boolean (re-find #"running" (str (:stdout result))))))

(defn probe!
  "Functional behaviour, isolation, and bounds, in one machine-heavy pass."
  [{:keys [project-root outside-sentinel]}]
  (let [limits {:worker/timeout-ms 90000}
        original (slurp (io/file project-root "src" "a.txt"))
        zero (run!! project-root (shell "exit 0") {:limits limits})
        exact (run!! project-root (shell "exit 42") {:limits limits})
        streams (run!! project-root
                       (shell "echo to-stdout; echo to-stderr >&2")
                       {:limits limits})
        mutation (run!! project-root
                        (shell (str "echo overwritten > src/a.txt; "
                                    "rm src/b.txt; "
                                    "echo generated > src/c.txt; ls src"))
                        {:limits limits})
        confined (run!! project-root
                        (shell (str "cat " outside-sentinel " 2>&1; "
                                    "echo rc=$?"))
                        {:limits limits})
        network (run!! project-root
                       (shell (str "ip route | grep -c default; "
                                   "wget -T 3 -q -O- http://1.1.1.1 2>&1; "
                                   "echo wget-rc=$?"))
                       {:limits limits})
        environment (run!! project-root (shell "env | sort")
                           {:limits limits
                            :environment {"BBAGENT_DECLARED" "declared-value"}})
        bounded (run!! project-root
                       (shell "yes abcdefghij | head -n 20000")
                       {:limits (assoc limits :worker/stdout-max-bytes 4096)})
        coordinate-before (:project/input-coordinate zero)
        ;; Taken before the host edit below, not inside the result map:
        ;; the map is built after that edit has already moved the tree.
        coordinate-unmoved (snapshot/coordinate project-root)]
    ;; Unique per run, so the phase is re-runnable against a project it has
    ;; already edited: identical content would leave the coordinate where it
    ;; was and the sensitivity check would be asserting nothing.
    (spit (io/file project-root "src" "edited.txt")
          (str "a change the host made " (System/nanoTime)))
    (let [after (run!! project-root (shell "cat src/edited.txt") {:limits limits})]
      {:worker/runtime worker/executable
       :worker/version (:worker/version (worker/describe))
       :probe/exit-zero (verdict (and (= :completed (:status zero))
                                      (= 0 (:exit zero))))
       :probe/exit-exact (verdict (and (= :completed (:status exact))
                                       (= 42 (:exit exact))))
       :probe/streams-separated
       (verdict (and (str/includes? (:stdout streams) "to-stdout")
                     (not (str/includes? (:stdout streams) "to-stderr"))
                     (str/includes? (:stderr streams) "to-stderr")))
       :probe/workload-believed-it-wrote
       (verdict (and (= 0 (:exit mutation))
                     (str/includes? (:stdout mutation) "c.txt")
                     (not (str/includes? (:stdout mutation) "b.txt"))))
       :probe/host-project-unchanged
       (verdict (and (= original (slurp (io/file project-root "src" "a.txt")))
                     (.exists (io/file project-root "src" "b.txt"))
                     (not (.exists (io/file project-root "src" "c.txt")))))
       :probe/host-file-unreadable
       (verdict (and (not (str/includes? (:stdout confined) "SENTINEL"))
                     (str/includes? (:stdout confined) "rc=1")))
       :probe/network-unreachable
       (verdict (and (str/starts-with? (str/trim (:stdout network)) "0")
                     (str/includes? (:stdout network) "wget-rc=1")))
       :probe/environment-constructed
       (verdict (and (str/includes? (:stdout environment)
                                    "BBAGENT_DECLARED=declared-value")
                     (str/includes? (:stdout environment) "HOME=/root")
                     (not (re-find #"(?i)api_key|_token|_secret|password"
                                   (:stdout environment)))))
       :probe/stdout-bounded
       (verdict (and (true? (:stdout/truncated? bounded))
                     (<= (count (.getBytes ^String (:stdout bounded) "UTF-8"))
                         4096)
                     (= 220000 (:stdout/bytes bounded))))
       :probe/duration-reported (verdict (pos? (:duration-ms zero)))
       :probe/coordinate-stable
       (verdict (and (true? (:project/input-stable? zero))
                     (= coordinate-before (:project/input-coordinate exact))
                     (= coordinate-before coordinate-unmoved)))
       :probe/coordinate-sensitive
       (verdict (and (not= coordinate-before (:project/input-coordinate after))
                     (str/includes? (:stdout after) "a change the host made")))
       :probe/uncommitted-work-visible
       (verdict (str/includes? (:stdout after) "a change the host made"))})))

(defn timeout!
  "The deadline, and what is left running after it."
  [{:keys [project-root]}]
  (let [chose (run!! project-root (shell "exit 124")
                     {:limits {:worker/timeout-ms 60000}})
        deadline (run!! project-root (shell "sleep 300")
                        {:limits {:worker/timeout-ms 5000}})
        beat (io/file project-root "..")
        heartbeat (io/file (str beat) "a3a-heartbeat")
        _ (.mkdirs heartbeat)
        reaping (process/execute!
                 {:argv [worker/executable "machine" "run"
                         "-v" (str heartbeat ":/beat")
                         "--" "/bin/sh" "-c"
                         (str "while true; do echo t >> /beat/tick; "
                              "sleep 0.2; done & sleep 300")]
                  :timeout-ms 6000
                  :inherit-environment? true})
        tick (io/file heartbeat "tick")
        at-deadline (count (line-seq (io/reader tick)))
        _ (Thread/sleep 4000)
        later (count (line-seq (io/reader tick)))]
    {:timeout/program-chose-124 (verdict (and (= :completed (:status chose))
                                              (= 124 (:exit chose))))
     :timeout/deadline-is-distinct
     (verdict (and (= :timeout (:status deadline))
                   (not (contains? deadline :exit))
                   (not= (:status chose) (:status deadline))))
     :timeout/disposition-terminated
     (verdict (= :terminated (:worker/disposition deadline)))
     :timeout/workload-was-running (verdict (pos? at-deadline))
     :timeout/in-machine-child-stopped (verdict (= at-deadline later))
     :timeout/nothing-left-running (verdict (not (machines-running?)))
     :timeout/tick-count at-deadline}))

(defn dogfood!
  "A real project, a real command, and a mutation that must not escape.

   The command is a babashka script the project actually keeps, checking
   invariants the project actually has, run against the working tree
   including whatever is uncommitted in it."
  [{:keys [project-root tools]}]
  (let [before (snapshot/manifest project-root
                                  {:exclusions (conj snapshot/default-exclusions
                                                     ".cpcache" "target")})
        options {:limits {:worker/timeout-ms 180000}
                 :tools tools
                 :snapshot {:exclusions (conj snapshot/default-exclusions
                                              ".cpcache" "target")}}
        check (run!! project-root ["bb" "script/a3a-source-check.clj"] options)
        vandal (run!! project-root
                      (shell (str "rm -rf src/bbagent; "
                                  "echo destroyed > README.md; "
                                  "echo generated > WORKER-WAS-HERE.txt; "
                                  "ls src 2>&1 | head -2"))
                      options)
        after (snapshot/manifest project-root
                                 {:exclusions (conj snapshot/default-exclusions
                                                    ".cpcache" "target")})]
    {:dogfood/project-root (str project-root)
     :dogfood/entry-count (:snapshot/entry-count before)
     :dogfood/bytes (:snapshot/bytes before)
     :dogfood/input-coordinate (:snapshot/coordinate before)
     :dogfood/argv ["bb" "script/a3a-source-check.clj"]
     :dogfood/exit (:exit check)
     :dogfood/duration-ms (:duration-ms check)
     :dogfood/stdout (str/trim (:stdout check))
     :dogfood/check-passed
     (verdict (and (= :completed (:status check))
                   (= 0 (:exit check))
                   (str/includes? (:stdout check) "a3a-source-check OK")))
     :dogfood/coordinate-recorded
     (verdict (= (:snapshot/coordinate before)
                 (:project/input-coordinate check)))
     :dogfood/vandal-believed-it-succeeded
     (verdict (= :completed (:status vandal)))
     :dogfood/host-project-intact
     (verdict (and (= (:snapshot/coordinate before)
                      (:snapshot/coordinate after))
                   (.exists (io/file project-root "src" "bbagent" "worker.clj"))
                   (not (.exists (io/file project-root "WORKER-WAS-HERE.txt")))))}))
