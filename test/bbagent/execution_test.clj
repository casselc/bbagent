(ns bbagent.execution-test
  "A3b: the semantic layer over the proven execution substrate.

   These tests use a stub execution environment rather than a machine.  That
   is deliberate: what they are about is the layer between the model and the
   substrate -- what it accepts, what it refuses, what it turns a worker
   result into, and what it does on replay -- and a real machine would make
   every one of those assertions slower without making any of them stronger.
   The substrate's own properties are proven against a real machine in
   bbagent.worker-test."
  (:require [bb4t.catalog :as catalog]
            [bb4t.execution :as execution]
            [bbagent.bb4t :as app-runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:private stub-description
  {:executor/type :test/stub
   :executor/network :none
   :executor/version "0.0.0-test"})

(defrecord StubEnvironment [result requests]
  execution/ExecutionEnvironment
  (-describe [_] stub-description)
  (-execute [_ request]
    (swap! requests conj request)
    (if (fn? result) (result request) result)))

(defn- stub
  ([] (stub {}))
  ([overrides]
   (->StubEnvironment
    (if (fn? overrides)
      overrides
      (merge {:status :completed
              :exit 0
              :duration-ms 12
              :stdout "ok\n" :stdout/bytes 3 :stdout/truncated? false
              :stderr "" :stderr/bytes 0 :stderr/truncated? false
              :worker/disposition :terminated
              :project/input-stable? true
              :project/input-coordinate "sha256:aaa"}
             overrides))
    (atom []))))

(defn- temp-project []
  (let [root (str (Files/createTempDirectory "bbagent-execution"
                                             (make-array FileAttribute 0)))]
    (spit (io/file root "README.md") "hello\n")
    root))

(defn- session!
  "A fresh runtime and Context over a throwaway project.

   Named with a bang because it builds one; a local called `app` shadowing a
   function called `app` was the first thing that went wrong here, and a
   verb-shaped name for the builder makes that impossible to write."
  ([environment] (session! environment :agent/project-execute))
  ([environment profile]
   (app-runtime/create (temp-project) profile {:environment environment})))

(defn- run-value [app source]
  (get-in (app-runtime/evaluate app source) [:evaluation :value :value/data]))

(defn- run-error [app source]
  (let [result (app-runtime/evaluate app source)]
    (is (= :error (:status result)) (pr-str result))
    (:error result)))

;; ---------------------------------------------------------------------------
;; The capability exists in exactly one profile

(deftest project-run-is-not-in-the-frozen-profiles
  (doseq [profile [:agent/project-read :agent/project-survey
                   :agent/project-develop]]
    (testing (str profile)
      (is (not (contains? (app-runtime/capabilities profile) :project/run))
          "a frozen profile gained execution authority")
      (is (not (contains? (get-in catalog/profiles
                                  [profile :profile/max-capabilities])
                          :project/run))))))

(deftest project-run-is-in-the-execution-profile
  (is (contains? (app-runtime/capabilities :agent/project-execute)
                 :project/run))
  (is (= (conj (app-runtime/capabilities :agent/project-develop) :project/run)
         (app-runtime/capabilities :agent/project-execute))
      "the execution profile is project-develop plus exactly one operation"))

(deftest executing-is-classified-so-recovery-will-not-repeat-it
  (is (= :actuation (catalog/effect-kind :project/execute))))

(deftest the-frozen-profiles-are-untouched
  (is (= #{:data/json-read :data/json-write :project/read}
         (get-in catalog/profiles
                 [:agent/project-read :profile/max-capabilities])))
  (is (= #{:data/json-read :data/json-write :project/read :project/list
           :project/search :project/stat}
         (get-in catalog/profiles
                 [:agent/project-survey :profile/max-capabilities])))
  (is (= #{:data/json-read :data/json-write :project/read :project/list
           :project/search :project/stat :project/edit}
         (get-in catalog/profiles
                 [:agent/project-develop :profile/max-capabilities]))))

;; ---------------------------------------------------------------------------
;; No environment, no Context

(deftest an-executing-context-without-an-environment-is-refused
  (let [failure (try
                  (app-runtime/create (temp-project) :agent/project-execute
                                      {:environment nil
                                       :executor {:tools "/nonexistent"}})
                  (catch Exception failure failure))]
    (is (instance? Exception failure)
        "an executing profile was created with no execution environment")))

(deftest an-environment-that-cannot-describe-itself-is-refused
  (let [broken (reify execution/ExecutionEnvironment
                 (-describe [_] {:executor/type (Object.)})
                 (-execute [_ _] {}))]
    (is (thrown? Exception
                 (app-runtime/create (temp-project) :agent/project-execute
                                     {:environment broken})))))

;; ---------------------------------------------------------------------------
;; What the model may say

(deftest argv-must-be-a-bounded-vector-of-real-arguments
  (let [app (session! (stub))]
    (doseq [source ["(project/run {})"
                    "(project/run {:argv []})"
                    "(project/run {:argv [\"\"]})"
                    "(project/run {:argv [\"bb\" 3]})"
                    "(project/run {:argv \"bb\"})"]]
      (testing source
        (is (= :bb4t-evaluation-failure
               (:bbagent/error (run-error app source))))))))

(deftest unknown-options-are-refused-rather-than-ignored
  (let [app (session! (stub))]
    (is (= :bb4t-evaluation-failure
           (:bbagent/error
            (run-error app "(project/run {:argv [\"bb\"] :tools \"/home\"})"))))
    (is (= :bb4t-evaluation-failure
           (:bbagent/error
            (run-error app
                       "(project/run {:argv [\"bb\"] :project-root \"/\"})"))))))

(deftest cwd-cannot-leave-the-project
  (let [app (session! (stub))]
    (doseq [cwd ["/etc" ".." "../elsewhere" "src/../.."]]
      (testing cwd
        (is (= :bb4t-evaluation-failure
               (:bbagent/error
                (run-error app (str "(project/run {:argv [\"bb\"] :cwd "
                                    (pr-str cwd) "})")))))))))

(deftest cwd-defaults-to-the-project-root
  (let [environment (stub)
        app (session! environment)]
    (run-value app "(project/run {:argv [\"bb\"]})")
    (is (= "." (:cwd (first @(:requests environment)))))))

(deftest a-timeout-may-not-exceed-the-context-limit
  (let [environment (stub)
        app (session! environment)]
    (is (= :bb4t-evaluation-failure
           (:bbagent/error
            (run-error app "(project/run {:argv [\"bb\"] :timeout-ms 300001})"))))
    (run-value app "(project/run {:argv [\"bb\"] :timeout-ms 1000})")
    (is (= 1000 (:timeout-ms (first @(:requests environment)))))))

(deftest a-timeout-defaults-to-the-context-limit
  (let [environment (stub)
        app (session! environment)]
    (run-value app "(project/run {:argv [\"bb\"]})")
    (is (= 300000 (:timeout-ms (first @(:requests environment)))))))

(deftest the-stream-budgets-come-from-the-context-and-not-the-caller
  (let [environment (stub)
        app (session! environment)]
    (run-value app "(project/run {:argv [\"bb\"]})")
    (let [request (first @(:requests environment))]
      (is (= 1048576 (:stdout-max-bytes request)))
      (is (= 1048576 (:stderr-max-bytes request))))))

(deftest the-project-root-is-supplied-by-the-runtime
  (let [environment (stub)
        app (session! environment)]
    (run-value app "(project/run {:argv [\"bb\"]})")
    (is (some? (:project/root (first @(:requests environment))))
        "the executor was not told which project to run against")))

;; ---------------------------------------------------------------------------
;; What the model gets back

(deftest a-completed-run-carries-its-exit-and-its-project-coordinate
  (let [result (run-value (session! (stub)) "(project/run {:argv [\"bb\"]})")]
    (is (= :completed (:status result)))
    (is (= 0 (:exit result)))
    (is (= "ok\n" (:stdout result)))
    (is (true? (:project/input-stable? result)))
    (is (= "sha256:aaa" (:project/input-coordinate result)))
    (is (string? (:executor/coordinate result)))
    (is (= :terminated (:worker/disposition result)))))

(deftest a-nonzero-exit-is-a-completed-run
  (let [result (run-value (session! (stub {:exit 7})) "(project/run {:argv [\"bb\"]})")]
    (is (= :completed (:status result)))
    (is (= 7 (:exit result)))))

(deftest a-deadline-is-not-a-program-that-chose-a-number
  (let [result (run-value (session! (stub {:status :timeout
                                      :exit nil
                                      :project/input-coordinate "sha256:aaa"}))
                          "(project/run {:argv [\"bb\"]})")]
    (is (= :timeout (:status result)))
    (is (nil? (:exit result)) "a timed-out run reported an exit code")))

(deftest a-run-that-never-started-is-distinct-from-one-that-failed
  (let [result (run-value (session! (stub {:status :worker-failure
                                      :exit nil
                                      :worker/error "overlay"}))
                          "(project/run {:argv [\"bb\"]})")]
    (is (= :worker-failure (:status result)))
    (is (nil? (:exit result)))
    (is (= "overlay" (:worker/error result)))))

(deftest an-unknown-status-fails-closed
  (is (= :bb4t-evaluation-failure
         (:bbagent/error
          (run-error (session! (stub {:status :fine}))
                     "(project/run {:argv [\"bb\"]})")))))

(deftest an-environment-that-ignored-its-budget-fails-closed
  (is (= :bb4t-evaluation-failure
         (:bbagent/error
          (run-error (session! (stub {:stdout (str/join (repeat 1048577 "x"))}))
                     "(project/run {:argv [\"bb\"]})")))))

;; ---------------------------------------------------------------------------
;; A project that moved is not a project that was verified

(deftest a-changed-project-cannot-look-like-a-successful-verification
  (let [result (run-value (session! (stub {:project/input-stable? false
                                      :project/input-coordinate nil}))
                          "(project/run {:argv [\"bb\"]})")]
    (is (= :project-changed (:status result)))
    (is (nil? (:exit result))
        "an unanchored run reported :exit, which reads as ordinary success")
    (is (= :completed (:process/status result)))
    (is (= 0 (:process/exit result)))
    (is (false? (:project/input-stable? result)))
    (is (nil? (:project/input-coordinate result))
        "an unanchored run named a project state it did not entirely see")
    (is (= "ok\n" (:stdout result))
        "the output the command produced is still reported")))

(deftest a-changed-project-outranks-a-deadline-too
  (let [result (run-value (session! (stub {:status :timeout
                                      :exit nil
                                      :project/input-stable? false
                                      :project/input-coordinate nil}))
                          "(project/run {:argv [\"bb\"]})")]
    (is (= :project-changed (:status result)))
    (is (= :timeout (:process/status result)))
    (is (nil? (:process/exit result)))))

;; ---------------------------------------------------------------------------
;; Recovery reproduces a run; it never performs one

(deftest a-recorded-run-is-restored-without-being-performed-again
  (let [environment (stub)
        app (session! environment)
        source "(def verification (project/run {:argv [\"bb\" \"check\"]}))"
        recorded (app-runtime/evaluate app source {:transcript :record})
        receipts (:operations recorded)]
    (is (= :ok (:status recorded)))
    (is (= 1 (count @(:requests environment))))
    (is (= 1 (count receipts)))
    (is (= :project/run (:operation/id (first receipts))))
    (testing "a fresh Context rebuilt from the receipts"
      (let [resumed (session! environment)
            replayed (app-runtime/evaluate resumed source
                                           {:transcript :replay
                                            :receipts receipts})]
        (is (= :ok (:status replayed)) (pr-str replayed))
        (is (= 1 (count @(:requests environment)))
            "replay ran the command a second time")
        (is (= (get-in recorded [:evaluation :value :value/data])
               (get-in replayed [:evaluation :value :value/data]))
            "the restored result differs from the recorded one")
        (testing "and the restored value is still bound in the Context"
          (is (= 0 (get-in (app-runtime/evaluate resumed "(:exit verification)")
                           [:evaluation :value :value/data]))))))))

(deftest a-historical-run-with-no-receipt-refuses-rather-than-running
  (let [environment (stub)
        app (session! environment)
        result (app-runtime/evaluate app "(project/run {:argv [\"bb\"]})"
                                     {:transcript :legacy})]
    (is (= :error (:status result)))
    (is (= :actuation-without-transcript (:transcript/error result)))
    (is (= 0 (count @(:requests environment)))
        "a legacy replay executed a command it had no receipt for")))

(deftest a-replay-whose-arguments-moved-fails-closed
  (let [environment (stub)
        app (session! environment)
        recorded (app-runtime/evaluate app "(project/run {:argv [\"bb\" \"a\"]})"
                                       {:transcript :record})
        resumed (session! environment)
        replayed (app-runtime/evaluate resumed
                                       "(project/run {:argv [\"bb\" \"b\"]})"
                                       {:transcript :replay
                                        :receipts (:operations recorded)})]
    (is (= :error (:status replayed)))
    (is (= :args-mismatch (:transcript/error replayed)))
    (is (= 1 (count @(:requests environment))))))

;; ---------------------------------------------------------------------------
;; The environment is named in the Context's own identity

(deftest the-context-coordinate-covers-the-execution-environment
  (let [app (session! (stub))
        executor (get-in app [:context/description :context/effective
                              :context/resources :executor])]
    (is (= :execution/environment (:resource/type executor)))
    (is (= stub-description (:execution/description executor)))
    (is (str/starts-with? (:execution/coordinate executor) "sha256:"))
    (is (not (str/includes? (pr-str executor) "/tmp"))
        "the execution environment description leaked a host path")))

(deftest a-different-environment-is-a-different-context
  (let [one (session! (stub))
        other (->StubEnvironment {:status :completed} (atom []))]
    (is (not= (get-in one [:context/description :context/coordinate])
              (get-in (session! (assoc other :description
                                  (assoc stub-description
                                         :executor/version "9.9.9")))
                      [:context/description :context/coordinate]))
        "two materially different execution environments share a coordinate")))

(deftest the-surface-still-projects-no-classes
  (let [surface (get-in (session! (stub)) [:context/description :context/surface])]
    (is (= 0 (:projected-class-count surface)))
    (is (= 0 (:supplied-import-count surface)))
    (is (contains? (set (map :sci/var (:projections surface))) 'project/run))))
