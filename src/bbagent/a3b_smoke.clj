(ns bbagent.a3b-smoke
  "A3b evidence, taken through the model-facing path in the native product.

   A3a proved the substrate by calling it directly.  Nothing here does.
   Every gate below goes in through a bounded SCI Context evaluating
   `project/run`, because the question A3b asks is not whether the worker
   isolates -- that is answered -- but whether one semantic operation can
   hand that isolation to a model without becoming a second way to reach
   the host."
  (:require [bb4t.execution :as execution]
            [bbagent.bb4t :as app-runtime]
            [bbagent.executor :as executor]
            [bbagent.process :as process]
            [bbagent.snapshot :as snapshot]
            [bbagent.worker :as worker]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- verdict [ok?] (if ok? :ok :failed))

(defn- session! [{:keys [project-root tools]}]
  (app-runtime/create project-root :agent/project-execute
                      {:executor {:tools tools}}))

(defn- evaluate [app source]
  (let [result (app-runtime/evaluate app source)]
    (if (= :ok (:status result))
      (get-in result [:evaluation :value :value/data])
      {:status :evaluation-error :error (:error result)})))

(defn- run
  "One project/run, written the way a model would have to write it."
  ([app argv] (run app argv nil))
  ([app argv options]
   (evaluate app (str "(project/run " (pr-str (merge {:argv argv} options)) ")"))))

(defn- shell [script] ["/bin/sh" "-c" script])

(defn- machines-running? []
  (let [result (process/execute! {:argv [worker/executable "machine" "ls"]
                                  :timeout-ms 20000
                                  :inherit-environment? true})]
    (boolean (re-find #"running" (str (:stdout result))))))

(defn describe!
  "What execution environment this host can authorize, and what names it."
  [options]
  (let [environment (executor/create {:tools (:tools options)})
        {:keys [description coordinate]} (execution/describe environment)]
    {:executor/coordinate coordinate
     :executor/description description
     :executor/available? true}))

(defn version!
  "An unmeasured machine manager is refused rather than assumed equivalent."
  [{:keys [tools]}]
  (let [refused (try
                  (executor/create {:tools tools
                                    :approved-versions #{"0.0.0-not-this-one"}})
                  :created
                  (catch Exception failure (ex-message failure)))
        overridden (try
                     (:executor/approval
                      (:description
                       (execution/describe
                        (executor/create {:tools tools
                                          :approved-versions #{"0.0.0-nope"}
                                          :allow-unapproved-version? true}))))
                     (catch Exception failure (ex-message failure)))
        approved (:executor/approval
                  (:description
                   (execution/describe (executor/create {:tools tools}))))
        missing-bundle (try
                         (executor/create {:tools nil})
                         :created
                         (catch Exception failure (ex-message failure)))]
    {:version/approved-set (vec (sort executor/approved-versions))
     :version/unapproved-refused
     (verdict (and (string? refused)
                   (str/includes? refused "has not been measured")))
     :version/override-is-recorded (verdict (= :host-override overridden))
     :version/approved-is-recognized (verdict (= :recognized approved))
     :version/tool-bundle-required
     (verdict (and (string? missing-bundle)
                   (str/includes? missing-bundle "tool bundle")))
     :version/no-executor-no-context
     (verdict (try
                (app-runtime/create "." :agent/project-execute
                                    {:executor {:tools nil}})
                false
                (catch Exception _ true)))}))

(defn authority!
  "The new profile gains one operation and nothing else."
  [{:keys [project-root tools]}]
  (let [app (session! {:project-root project-root :tools tools})
        description (:context/description app)
        surface (:context/surface description)
        grants (get-in description [:context/effective :context/grants])
        negatives
        (into (sorted-map)
              (map (fn [[probe source]] [probe (app-runtime/evaluate app source)]))
              {"bbagent.worker/require" "(require '[bbagent.worker :as worker])"
               "bbagent.worker/execute!"
               "(bbagent.worker/execute! {:project-root \".\" :argv [\"true\"]})"
               "bbagent.process/execute!"
               "(bbagent.process/execute! {:argv [\"true\"] :timeout-ms 1000})"
               "bbagent.snapshot/manifest" "(bbagent.snapshot/manifest \".\")"
               "bbagent.executor/require" "(require '[bbagent.executor :as e])"
               "bbagent.executor/create" "(bbagent.executor/create {:tools \"/\"})"
               "bb4t.execution/require" "(require '[bb4t.execution :as x])"
               "java.lang.ProcessBuilder" "java.lang.ProcessBuilder"
               "java.lang.Runtime/getRuntime" "(java.lang.Runtime/getRuntime)"
               "java.lang.ProcessHandle" "java.lang.ProcessHandle"
               "clojure.java.shell/require" "(require '[clojure.java.shell :as s])"
               "process/run" "(process/run {:argv [\"true\"]})"
               "smolvm/run" "(smolvm/run {:argv [\"true\"]})"
               ;; The host settings a run is bounded by are not arguments.
               "project/run tools"
               "(project/run {:argv [\"true\"] :tools \"/home\"})"
               "project/run project-root"
               "(project/run {:argv [\"true\"] :project-root \"/\"})"
               "project/run environment"
               "(project/run {:argv [\"true\"] :environment {\"X\" \"1\"}})"
               "project/run network"
               "(project/run {:argv [\"true\"] :network true})"
               "project/run absolute cwd"
               "(project/run {:argv [\"true\"] :cwd \"/etc\"})"
               "project/run escaping cwd"
               "(project/run {:argv [\"true\"] :cwd \"../..\"})"
               "project/run over the timeout limit"
               "(project/run {:argv [\"true\"] :timeout-ms 300001})"})]
    {:authority/profile (get-in description [:context/spec :profile])
     :authority/grants (vec (sort grants))
     :authority/run-granted (verdict (contains? grants :project/run))
     :authority/develop-plus-one
     (verdict (= (conj (app-runtime/capabilities :agent/project-develop)
                       :project/run)
                 grants))
     :authority/projected-class-count (:projected-class-count surface)
     :authority/supplied-import-count (:supplied-import-count surface)
     :authority/negative-count (count negatives)
     :authority/all-refused
     (verdict (= #{:error} (set (map :status (vals negatives)))))
     :authority/refused-for-the-right-reason
     (verdict (= #{:bb4t-evaluation-failure}
                 (set (map #(get-in % [:error :bbagent/error])
                           (vals negatives)))))
     :authority/executor-coordinate
     (get-in description [:context/effective :context/resources
                          :executor :execution/coordinate])
     ;; A slash appears in every qualified keyword, so the check is for a
     ;; string that begins with one -- an absolute path -- and for the host's
     ;; own home directory by name.
     :authority/no-host-path-in-executor
     (let [printed (pr-str (get-in description [:context/effective
                                                :context/resources :executor
                                                :execution/description]))]
       (verdict (and (nil? (re-find #"\"/" printed))
                     (not (str/includes? printed
                                         (System/getProperty "user.home"))))))}))

(defn probe!
  "The A3a isolation properties, re-proven from where the model stands."
  [{:keys [project-root tools outside-sentinel]}]
  (let [app (session! {:project-root project-root :tools tools})
        executor-coordinate
        (get-in app [:context/description :context/effective :context/resources
                     :executor :execution/coordinate])
        original (slurp (io/file project-root "src" "a.txt"))
        zero (run app (shell "exit 0") {:timeout-ms 90000})
        exact (run app (shell "exit 42") {:timeout-ms 90000})
        streams (run app (shell "echo to-stdout; echo to-stderr >&2")
                     {:timeout-ms 90000})
        vandal (run app (shell (str "echo overwritten > src/a.txt; "
                                    "rm src/b.txt; "
                                    "echo generated > src/c.txt; ls src"))
                    {:timeout-ms 90000})
        confined (run app (shell (str "cat " outside-sentinel " 2>&1; echo rc=$?"))
                      {:timeout-ms 90000})
        network (run app (shell (str "ip route | grep -c default; "
                                     "wget -T 3 -q -O- http://1.1.1.1 2>&1; "
                                     "echo wget-rc=$?"))
                     {:timeout-ms 90000})
        hidden (run app (shell (str "ls -a /work | tr '\\n' ' '; echo; "
                                    "ls -a /input | tr '\\n' ' '; echo; "
                                    "cat /work/.git/config 2>&1"))
                    {:timeout-ms 90000})
        deadline (run app (shell "sleep 300") {:timeout-ms 5000})
        chose (run app (shell "exit 124") {:timeout-ms 60000})
        cwd (run app ["/bin/sh" "-c" "pwd"] {:cwd "src" :timeout-ms 60000})
        lines (str/split-lines (str (:stdout hidden)))]
    {:probe/exit-zero (verdict (and (= :completed (:status zero))
                                    (= 0 (:exit zero))))
     :probe/exit-exact (verdict (and (= :completed (:status exact))
                                     (= 42 (:exit exact))))
     :probe/streams-separated
     (verdict (and (str/includes? (:stdout streams) "to-stdout")
                   (not (str/includes? (:stdout streams) "to-stderr"))
                   (str/includes? (:stderr streams) "to-stderr")))
     :probe/workload-believed-it-wrote
     (verdict (and (= 0 (:exit vandal))
                   (str/includes? (:stdout vandal) "c.txt")
                   (not (str/includes? (:stdout vandal) "b.txt"))))
     :probe/host-checkout-unchanged
     (verdict (and (= original (slurp (io/file project-root "src" "a.txt")))
                   (.exists (io/file project-root "src" "b.txt"))
                   (not (.exists (io/file project-root "src" "c.txt")))))
     :probe/host-file-unreadable
     (verdict (and (not (str/includes? (:stdout confined) "SENTINEL"))
                   (str/includes? (:stdout confined) "rc=1")))
     :probe/network-unreachable
     (verdict (and (str/starts-with? (str/trim (:stdout network)) "0")
                   (str/includes? (:stdout network) "wget-rc=1")))
     :probe/excluded-paths-invisible
     (verdict (and (not (str/includes? (str (first lines)) ".git"))
                   (= #{"." ".."}
                      (set (remove str/blank?
                                   (str/split (str (second lines)) #"\s+"))))
                   (str/includes? (:stdout hidden) "No such file")))
     :probe/deadline-is-distinct
     (verdict (and (= :timeout (:status deadline))
                   (nil? (:exit deadline))
                   (= :terminated (:worker/disposition deadline))))
     :probe/program-chose-124
     (verdict (and (= :completed (:status chose)) (= 124 (:exit chose))))
     :probe/cwd-is-honoured
     (verdict (= "/work/src" (str/trim (str (:stdout cwd)))))
     :probe/input-coordinate-present
     (verdict (and (string? (:project/input-coordinate zero))
                   (= (:project/input-coordinate zero)
                      (:project/input-coordinate exact))))
     :probe/executor-coordinate-recorded
     (verdict (= executor-coordinate (:executor/coordinate zero)))
     :probe/nothing-left-running (verdict (not (machines-running?)))}))

(defn unstable!
  "A project that moved while a command ran is not a verified project."
  [{:keys [project-root tools]}]
  (let [app (session! {:project-root project-root :tools tools})
        moved (future
                (Thread/sleep 2500)
                (spit (io/file project-root "src" "moved.txt")
                      (str "changed mid-run " (System/nanoTime))))
        during (run app (shell "sleep 6; exit 0") {:timeout-ms 60000})
        _ @moved
        after (run app (shell "exit 0") {:timeout-ms 60000})]
    {:unstable/status (:status during)
     :unstable/not-ordinary-success
     (verdict (and (= :project-changed (:status during))
                   (nil? (:exit during))
                   (false? (:project/input-stable? during))))
     :unstable/process-outcome-preserved
     (verdict (and (= :completed (:process/status during))
                   (= 0 (:process/exit during))))
     :unstable/no-coordinate-claimed
     (verdict (nil? (:project/input-coordinate during)))
     :unstable/a-later-run-is-anchored-again
     (verdict (and (= :completed (:status after))
                   (true? (:project/input-stable? after))
                   (string? (:project/input-coordinate after))))}))

(defn replay!
  "Recovery reconstructs what a run returned, and never runs it again."
  [{:keys [project-root tools]}]
  (let [environment (executor/create {:tools tools})
        make (fn [] (app-runtime/create project-root :agent/project-execute
                                        {:environment environment}))
        source (str "(def verification (project/run {:argv [\"bb\" \"--version\"]"
                    " :timeout-ms 120000}))")
        app (make)
        recorded (app-runtime/evaluate app source {:transcript :record})
        after-record (executor/invocation-count environment)
        receipts (:operations recorded)
        resumed (make)
        replayed (app-runtime/evaluate resumed source
                                       {:transcript :replay :receipts receipts})
        after-replay (executor/invocation-count environment)
        restored (app-runtime/evaluate resumed "verification")
        legacy-app (make)
        legacy (app-runtime/evaluate legacy-app source {:transcript :legacy})
        after-legacy (executor/invocation-count environment)]
    {:replay/recorded-status (:status recorded)
     :replay/executions-after-record after-record
     :replay/executions-after-replay after-replay
     :replay/executions-after-legacy after-legacy
     :replay/one-execution (verdict (= 1 after-record))
     :replay/replay-succeeded (verdict (= :ok (:status replayed)))
     :replay/no-second-execution (verdict (= after-record after-replay))
     :replay/result-restored
     (verdict (= (:result (first receipts))
                 (get-in restored [:evaluation :value :value/data])))
     :replay/exit-restored
     (verdict (= 0 (:exit (get-in restored [:evaluation :value :value/data]))))
     :replay/legacy-refused
     (verdict (= :actuation-without-transcript (:transcript/error legacy)))
     :replay/legacy-did-not-execute (verdict (= after-replay after-legacy))}))

(defn dogfood!
  "A real project, a real check, chosen and run the way a model would."
  [{:keys [project-root tools]}]
  (let [app (session! {:project-root project-root :tools tools})
        exclusions (conj snapshot/default-exclusions ".cpcache" "target")
        before (snapshot/manifest project-root {:exclusions exclusions})
        ;; Composed rather than called: the product thesis is that the model
        ;; builds task vocabulary out of the primitives it was granted, and a
        ;; check it can name is the smallest instance of that.
        _ (app-runtime/evaluate
           app (str "(defn check [] (project/run {:argv [\"bb\" "
                    "\"script/a3a-source-check.clj\"] :timeout-ms 180000}))"))
        check (evaluate app "(check)")
        vandal (run app (shell (str "rm -rf src/bbagent; "
                                    "echo destroyed > README.md; "
                                    "echo generated > WORKER-WAS-HERE.txt; "
                                    "ls src 2>&1 | head -2"))
                    {:timeout-ms 180000})
        after (snapshot/manifest project-root {:exclusions exclusions})]
    {:dogfood/entry-count (:snapshot/entry-count before)
     :dogfood/exit (:exit check)
     :dogfood/duration-ms (:duration-ms check)
     :dogfood/stdout (str/trim (str (:stdout check)))
     :dogfood/check-passed
     (verdict (and (= :completed (:status check))
                   (= 0 (:exit check))
                   (str/includes? (str (:stdout check)) "a3a-source-check OK")))
     :dogfood/composed-through-sci (verdict (map? check))
     :dogfood/vandal-believed-it-succeeded
     (verdict (= :completed (:status vandal)))
     :dogfood/host-project-intact
     (verdict (and (= (:snapshot/coordinate before)
                      (:snapshot/coordinate after))
                   (.exists (io/file project-root "src" "bbagent" "worker.clj"))
                   (not (.exists (io/file project-root "WORKER-WAS-HERE.txt")))))}))
