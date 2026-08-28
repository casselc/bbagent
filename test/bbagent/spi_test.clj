(ns bbagent.spi-test
  "Conformance for the ExecutionEnvironment EDN SPI.

  Two claims are checked here.  The first is conformance itself: the
  envelopes bbagent's keeper and adapter produce from the ported evidence
  inputs render byte-identically to the committed fixtures, the fixtures
  hash to their golden SHA-256 files, and every fixture parses and
  re-renders to itself.

  The second is that the envelopes claim nothing new.  Every canned input
  below is lifted from an existing suite -- the stub results of
  bbagent.execution-test, the truncation bounds and prelude failures of
  bbagent.worker-test, the refusal sites of bbagent.executor -- and the
  semantic assertions they carried (an exit only when the workload exited,
  a moved project carrying no coordinate, a replay that performs nothing)
  are restated against the envelope.  Where the kernel itself can be
  consulted, it is: the parity tests run the same stub through a real
  Context and compare what bb4t returned with what the envelope says."
  (:require [bb4t.canonical :as canonical]
             [bb4t.execution :as execution]
             [bbagent.bb4t :as app-runtime]
             [bbagent.coordinates :as coordinates]
             [bbagent.errors :as errors]
             [bbagent.executor :as executor]
             [bbagent.spi :as spi]
             [bbagent.spi-smolvm :as adapter]
             [bbagent.worker :as worker]
             [bbagent.worker-image :as worker-image]
             [clojure.java.io :as io]
             [clojure.string :as str]
             [clojure.test :refer [are deftest is testing]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; The ported evidence inputs.  These literals are the ones the fixtures
;; were generated from; if either side drifts, the byte comparison below
;; fails, which is the point of having fixtures at all.

(def ^:private hex64 (apply str (repeat 4 "0123456789abcdef")))
(def ^:private input-coordinate (str "sha256:" hex64))

(def ^:private stub-description
  ;; execution_test's stub-description, verbatim.
  {:executor/type :test/stub
   :executor/network :none
   :executor/version "0.0.0-test"})

(def ^:private smolvm-description
  ;; executor/create's published description shape, with the measured A3c
  ;; values pinned so the fixture is host-independent.
  {:executor/type :bbagent/smolvm-worker
   :executor/manager "smolvm"
   :executor/version "1.7.5"
   :executor/approval :recognized
   :executor/guest {:image :bbagent/worker-image
                    :image/digest (str "sha256:" hex64)
                    :privilege :unprivileged
                    :identity :derived-from-project-owner
                    :capabilities :none
                    :prelude :in-image
                    :prelude/contract "1"
                    :environment :constructed
                    :host-environment :not-inherited}
   :executor/network :none
   :executor/workspace {:model :overlayfs
                        :project-mount :read-only
                        :host-paths-mounted 1
                        :lifecycle :ephemeral-machine-per-execution
                        :excluded-paths :hidden-from-workload}
   :executor/exclusions [".git"]
   :executor/tools {:bundle :babashka/static
                    :location :in-image
                    :host-directory-mounted? false}
   :executor/ceilings {:worker/cpus 2
                       :worker/memory-mib 2048
                       :worker/stderr-max-bytes 1048576
                       :worker/stdout-max-bytes 1048576
                       :worker/timeout-ms 300000}})

(def ^:private completed-result
  ;; execution_test's stub result, widened to a full worker shape.
  {:status :completed
   :exit 0
   :duration-ms 12
   :stdout "ok\n" :stdout/bytes 3 :stdout/truncated? false
   :stderr "" :stderr/bytes 0 :stderr/truncated? false
   :worker/runtime "smolvm"
   :worker/disposition :terminated
   :project/input-stable? true
   :project/input-coordinate input-coordinate})

(def ^:private timeout-result
  ;; worker_test: a deadline is not a program that chose a number.
  {:status :timeout
   :duration-ms 4003
   :stdout "" :stdout/bytes 0 :stdout/truncated? false
   :stderr "" :stderr/bytes 0 :stderr/truncated? false
   :worker/disposition :terminated
   :project/input-stable? true
   :project/input-coordinate input-coordinate})

(def ^:private prelude-stderr "bbagent-worker: prelude failed: no/such/dir\n")

(def ^:private worker-failure-result
  ;; worker_test: a workspace that could not be entered never ran the
  ;; workload, so there is no exit status of its own to report.
  {:status :worker-failure
   :duration-ms 61
   :stdout "" :stdout/bytes 0 :stdout/truncated? false
   :stderr prelude-stderr
   :stderr/bytes (count (.getBytes ^String prelude-stderr "UTF-8"))
   :stderr/truncated? false
   :worker/disposition :terminated
   :worker/error (subs prelude-stderr 0 (dec (count prelude-stderr)))
   :project/input-stable? true
   :project/input-coordinate input-coordinate})

(def ^:private project-changed-result
  ;; worker_test: the project moved under the run; the coordinate that
  ;; would name a state it did not entirely see is absent, not blank.
  {:status :completed
   :exit 0
   :duration-ms 4021
   :stdout "changed mid-run\n" :stdout/bytes 16 :stdout/truncated? false
   :stderr "" :stderr/bytes 0 :stderr/truncated? false
   :worker/disposition :terminated
   :project/input-stable? false})

(def ^:private truncated-result
  ;; worker_test: what was kept is bounded; what is reported is the true
  ;; size (220000), not the kept size.
  {:status :completed
   :exit 0
   :duration-ms 812
   :stdout "yes: abcdefghij\n" :stdout/bytes 220000 :stdout/truncated? true
   :stderr "yes: ABCDEFGHIJ\n" :stderr/bytes 220000 :stderr/truncated? true
   :worker/disposition :terminated
   :project/input-stable? true
   :project/input-coordinate input-coordinate})

(def ^:private stub-reference
  {:environment/coordinate (spi/environment-coordinate stub-description)
   :environment/type :test/stub})

(defrecord StubEnvironment [description result invocations requests]
  execution/ExecutionEnvironment
  (-describe [_] description)
  ;; The invocation counter mirrors SmolvmExecutionEnvironment's exactly:
  ;; incremented before the run, so the count read afterwards is the
  ;; index of the run that just returned.
  (-execute [_ request]
    (swap! requests conj request)
    (swap! invocations inc)
    (if (fn? result) (result request) result)))

(defn- stub!
  ([] (stub! completed-result))
  ([result] (->StubEnvironment stub-description result (atom 0) (atom []))))

(def ^:private manager-unavailable-failure
  ;; approved-manager!'s first refusal site, verbatim.
  (errors/error :agent-invalid-action
                "No machine manager is available to run project commands"
                {:executor/manager "smolvm"
                 :executor/error "exit 1"}))

(def ^:private unmeasured-manager-failure
  ;; approved-manager!'s second refusal site.
  (errors/error :agent-invalid-action
                (str "The machine manager on this host is version 9.9.9, "
                     "whose isolation behaviour has not been measured")
                {:executor/manager "smolvm"
                 :executor/version "9.9.9"
                 :executor/approved #{"1.7.5"}}))

(def ^:private digest-mismatch-failure
  ;; guest-image!'s digest refusal site.
  (errors/error :agent-invalid-action
                (str "The guest image archive does not match the digest this "
                     "host pinned; execution refuses rather than running an "
                     "image nobody approved")
                {:image/expected (str "sha256:" hex64)
                 :image/actual (str "sha256:" (apply str (repeat 4 "fedcba9876543210")))}))

(def ^:private unreadable-image-failure
  ;; guest-image!'s unreadable refusal site.  The message carries a host
  ;; path; the envelope must not.
  (errors/error :agent-invalid-action
                "The guest image archive is not readable"
                {:error/message "NoSuchFileException: /secret/host/guest.tar"}))

(def ^:private root-owned-project-failure
  ;; project-identity!'s refusal site.
  (errors/error :agent-invalid-action
                (str "This project is owned by root, so there is no "
                     "unprivileged identity to run its commands as")
                {:project/uid 0}))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private fixture-directory "test/fixtures/spi-v1")

(defn- fixture-text [name]
  (slurp (str fixture-directory "/" name)))

(defn- conforms
  "The conformance relation, all three ways: byte-identical rendering,
  golden digest over the committed bytes, and an EDN round-trip that
  reproduces the bytes exactly."
  [name envelope]
  (testing (str name " renders byte-identically to its fixture")
    (is (= (fixture-text name) (str (spi/render envelope) "\n"))
        (pr-str envelope)))
  (testing (str name " matches its golden sha256")
    (let [[digest _] (str/split (str/trim (fixture-text (str name ".sha256"))) #"  ")]
      (is (= digest (spi/sha-256 (fixture-text name))))))
  (testing (str name " round-trips through EDN to itself")
    (is (= (fixture-text name)
           (str (spi/render (spi/read-envelope (fixture-text name))) "\n")))))

;; ---------------------------------------------------------------------------
;; The keeper: canonical rendering and coordinates

(deftest rendering-is-deterministic-and-order-independent
  (is (= "{:a 1, :b 2, :c 3}"
         (spi/render {:c 3 :b 2 :a 1})
         (spi/render (into (sorted-map) {:a 1 :c 3 :b 2}))))
  (is (= "{:a 1, :b {:x \"s\", :y [1, 2]}, :c #{:p, :q}}"
         (spi/render {:b {:y [1 2] :x "s"} :a 1 :c #{:q :p}})))
  (is (= "\"ok\\n\"" (spi/render "ok\n"))
      "a newline inside a string is bytes on the wire, not a line break")
  (is (not (str/includes? (spi/render {:s "line one\nline two"}) "\n"))))

(deftest values-outside-the-inert-domain-are-refused
  (doseq [value [1.5 (Object.) (with-meta [1] {:m 1})
                 (keyword "not a name") (map->StubEnvironment {})]]
    (testing (pr-str value)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"canonical EDN domain|metadata|not envelope data"
                            (spi/render value))))))

(deftest spi-coordinates-are-domain-separated
  (is (not= (spi/coordinate :spi.environment/description stub-description)
            (canonical/coordinate :bb4t/execution-environment stub-description))
      "an SPI coordinate collided with bb4t's over the same data")
  (is (not= (spi/coordinate :spi.environment/description stub-description)
            (coordinates/digest :bb4t/execution-environment stub-description))
      "an SPI coordinate collided with bbagent's over the same data")
  (is (not= (spi/coordinate :spi.environment/description stub-description)
            (spi/coordinate :spi.environment/availability stub-description))
      "two kinds shared a coordinate"))

(deftest the-keeper-depends-on-nothing-but-clojure
  ;; Independence is a property of the source, so it is checked against
  ;; the source: another repository has to be able to lift the keeper
  ;; whole, and a require of bb4t or bbagent would make that a lie.
  (let [requires (re-find #"\(:require[^)]*\)"
                          (slurp (io/resource "bbagent/spi.clj")))]
    (is (some? requires) "the keeper's ns form could not be read")
    (doseq [entry (rest (re-seq #"\[[^\]]+\]" requires))]
      (is (str/starts-with? (str/trim entry) "[clojure")
          (str "the keeper requires " entry)))))

;; ---------------------------------------------------------------------------
;; Describe and the environment coordinate

(deftest a-stub-description-conforms
  (conforms "describe-stub.edn" (spi/describe-envelope stub-description)))

(deftest the-smolvm-description-shape-conforms
  (conforms "describe-smolvm.edn" (adapter/describe-envelope smolvm-description)))

(deftest a-describe-envelope-names-its-own-description
  (let [envelope (spi/describe-envelope stub-description)]
    (is (= (spi/environment-coordinate stub-description)
           (:environment/coordinate envelope)))
    (is (= stub-description (:environment/description envelope)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not name the description"
                          (spi/validate (assoc envelope
                                               :environment/coordinate
                                               "sha256:0000000000000000000000000000000000000000000000000000000000000000"))))))

(deftest a-description-that-cannot-say-what-implements-it-is-refused
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names its type"
                        (adapter/describe-envelope {:executor/type "smolvm"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names its type"
                        (adapter/describe-envelope {:executor/network :none}))))

(deftest a-live-environment-describes-itself-through-the-adapter
  (let [envelope (adapter/describe-environment (stub!))]
    (is (spi/envelope? envelope))
    (is (= stub-description (:environment/description envelope)))
    (conforms "describe-stub.edn" envelope)))

;; ---------------------------------------------------------------------------
;; Availability and refusal

(deftest an-available-environment-conforms
  (conforms "availability-available.edn"
            (spi/available-envelope (:environment/coordinate stub-reference))))

(deftest a-refused-manager-conforms
  (conforms "availability-refused-manager.edn"
            (adapter/availability-from-failure manager-unavailable-failure)))

(deftest a-refused-image-digest-conforms
  (conforms "availability-refused-image-digest.edn"
            (adapter/availability-from-failure digest-mismatch-failure)))

(deftest every-executor-refusal-point-has-a-category
  ;; The five refusal sites of bbagent.executor, each with the data shape
  ;; its fail! leaves behind.  None of these is invented here; see
  ;; executor.clj's approved-manager!, guest-image! and project-identity!.
  (are [failure category] (= category (adapter/categorize failure))
    manager-unavailable-failure :spi.refusal/manager-unavailable
    unmeasured-manager-failure :spi.refusal/manager-unmeasured
    digest-mismatch-failure :spi.refusal/guest-image-digest-mismatch
    unreadable-image-failure :spi.refusal/guest-image-unusable
    root-owned-project-failure :spi.refusal/project-identity
    (ex-info "boom" {:something/else 1}) :spi.refusal/unknown))

(deftest a-refusal-carries-no-host-specifics
  ;; The unreadable-image failure's message names a host path; the refusal
  ;; envelope must paraphrase it, not forward it.
  (let [envelope (adapter/availability-from-failure unreadable-image-failure)]
    (is (false? (:environment/available? envelope)))
    (is (not (str/includes? (spi/render envelope) "/secret/host")))
    (is (not (str/includes? (spi/render envelope) "NoSuchFileException")))))

(deftest availability-says-exactly-one-thing
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"carries no refusal"
                        (-> (spi/available-envelope
                             (:environment/coordinate stub-reference))
                            (assoc :environment/refusal
                                   {:refusal/category :spi.refusal/unknown
                                    :refusal/reason "both"})
                            spi/validate)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"carries no coordinate"
                        (-> (spi/refusal-envelope :spi.refusal/unknown "why")
                            (assoc :environment/coordinate
                                   "sha256:0000000000000000000000000000000000000000000000000000000000000000")
                            spi/validate)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"known category"
                        (spi/refusal-envelope :spi.refusal/made-up "why"))))

(deftest a-live-probe-never-throws
  ;; Against a host with no usable guest image, the probe must answer with
  ;; a refusal envelope.  Which refusal depends on whether the host has a
  ;; manager at all, so both honest outcomes are accepted; a throw is not.
  (let [envelope (adapter/probe {:image "/nonexistent/spi-conformance.tar"})]
    (is (spi/envelope? envelope))
    (is (false? (:environment/available? envelope)))
    (is (contains? #{:spi.refusal/manager-unavailable
                     :spi.refusal/guest-image-unusable}
                   (get-in envelope [:environment/refusal :refusal/category])))
    (is (not (str/includes? (spi/render envelope) "/nonexistent")))))

(deftest a-live-host-with-a-measured-manager-probes-available
  ;; Ported evidence, not a new claim: with the same guest image and
  ;; manager the worker tests use, creation succeeds and the envelope
  ;; names the environment that was built.  Skipped, not weakened, when
  ;; the host has no manager.
  (when (:worker/available? (worker/describe))
    (let [root (str (Files/createTempDirectory "bbagent-spi-probe"
                                               (make-array FileAttribute 0)))
          envelope (adapter/probe {:image @worker-image/path
                                   :project-root root})]
      (is (true? (:environment/available? envelope)) (pr-str envelope))
      (is (re-matches #"sha256:[0-9a-f]{64}"
                      (:environment/coordinate envelope))))))

;; ---------------------------------------------------------------------------
;; Run envelopes: output, disposition, attribution

(defn- run-conforms [name result index]
  (conforms name (adapter/run-envelope result stub-reference index)))

(deftest a-completed-run-conforms
  (run-conforms "run-completed.edn" completed-result 1))

(deftest a-timed-out-run-conforms
  (run-conforms "run-timeout.edn" timeout-result 2))

(deftest a-worker-failure-conforms
  (run-conforms "run-worker-failure.edn" worker-failure-result 3))

(deftest a-changed-project-conforms
  (run-conforms "run-project-changed.edn" project-changed-result 4))

(deftest a-truncated-run-conforms
  (run-conforms "run-truncated.edn" truncated-result 5))

(deftest a-run-is-attributed-to-its-environment
  (let [envelope (adapter/run-envelope completed-result stub-reference 1)]
    (is (= (:environment/coordinate stub-reference)
           (get-in envelope [:run/attribution :environment/coordinate])))
    (is (= :test/stub (get-in envelope [:run/attribution :environment/type])))
    (is (= :terminated (:run/disposition envelope)))))

(deftest an-exit-survives-only-when-the-workload-exited
  (is (= 0 (:output/exit (adapter/run-envelope completed-result stub-reference 1))))
  (is (nil? (:output/exit (adapter/run-envelope timeout-result stub-reference 1))))
  (is (nil? (:output/exit (adapter/run-envelope worker-failure-result stub-reference 1))))
  (testing "and the envelope validator holds that line"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Only a completed run"
                          (spi/run-envelope (assoc (adapter/run-envelope
                                                    timeout-result stub-reference 1)
                                                   :output/exit 124))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"completed run carries its exit"
                          (spi/run-envelope (dissoc (adapter/run-envelope
                                                     completed-result stub-reference 1)
                                                    :output/exit))))))

(deftest a-changed-project-cannot-carry-a-coordinate-or-look-anchored
  (let [envelope (adapter/run-envelope project-changed-result stub-reference 1)]
    (is (= :project-changed (:output/status envelope)))
    (is (= {:input/stability :input/project-changed} (:run/input envelope))
        "an unanchored run named a project state it did not entirely see")
    (is (nil? (:output/exit envelope))
        "an unanchored run reported an exit, which reads as ordinary success")
    (is (= {:process/status :completed :process/exit 0}
           (:output/process envelope))
        "the process outcome was not demoted"))
  (testing "and a forged envelope that pairs them is refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same fact"
                          (spi/run-envelope
                           (assoc (adapter/run-envelope project-changed-result
                                                        stub-reference 1)
                                  :run/input {:input/coordinate input-coordinate}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"same fact"
                          (spi/run-envelope
                           (assoc (adapter/run-envelope completed-result
                                                        stub-reference 1)
                                  :run/input
                                  {:input/stability :input/project-changed}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing required keys"
                          (spi/run-envelope
                           (dissoc (adapter/run-envelope completed-result
                                                         stub-reference 1)
                                   :run/input))))))

(deftest truncated-streams-report-the-true-size
  (let [envelope (adapter/run-envelope truncated-result stub-reference 1)]
    (is (= 220000 (-> envelope :output/stdout :stream/bytes)))
    (is (= 220000 (-> envelope :output/stderr :stream/bytes)))
    (is (true? (-> envelope :output/stdout :stream/truncated?)))
    (is (true? (-> envelope :output/stderr :stream/truncated?)))))

(deftest an-unknown-worker-status-is-refused-rather-than-passed-through
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown status"
                        (adapter/run-envelope (assoc completed-result :status :fine)
                                              stub-reference 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown run status"
                        (spi/run-envelope {:run/invocation-index 1
                                           :run/attribution stub-reference
                                           :run/input {:input/coordinate input-coordinate}
                                           :output/status :fine
                                           :output/stdout {:stream/text "" :stream/bytes 0 :stream/truncated? false}
                                           :output/stderr {:stream/text "" :stream/bytes 0 :stream/truncated? false}
                                           :output/duration-ms 1
                                           :run/disposition :terminated}))))

;; ---------------------------------------------------------------------------
;; Parity with the kernel: same stub, both sides of the seam

(defn- temp-project []
  (let [root (str (Files/createTempDirectory "bbagent-spi"
                                             (make-array FileAttribute 0)))]
    (spit (io/file root "README.md") "hello\n")
    root))

(defn- session! [environment]
  (app-runtime/create (temp-project) :agent/project-execute
                      {:environment environment}))

(defn- run-value [app source]
  (get-in (app-runtime/evaluate app source) [:evaluation :value :value/data]))

(deftest the-envelope-agrees-with-what-the-kernel-returned
  ;; The stub runs through a real Context; the semantic result bb4t's
  ;; execution-result produced and the envelope the adapter produced from
  ;; the same worker result must say the same things.  This is
  ;; execution_test's completed-run and changed-project evidence, asserted
  ;; from both sides of the seam.
  (testing "a completed run"
    (let [semantic (run-value (session! (stub!)) "(project/run {:argv [\"bb\"]})")
          envelope (adapter/run-envelope completed-result stub-reference 1)]
      (is (= (:status semantic) (:output/status envelope)))
      (is (= (:exit semantic) (:output/exit envelope)))
      (is (= (:project/input-coordinate semantic)
             (get-in envelope [:run/input :input/coordinate])))
      (is (= (:stdout semantic) (-> envelope :output/stdout :stream/text)))
      (is (= (:stdout/bytes semantic) (-> envelope :output/stdout :stream/bytes)))
      (is (= (:worker/disposition semantic) (:run/disposition envelope)))))
  (testing "a run whose project moved"
    (let [semantic (run-value (session! (stub! project-changed-result))
                              "(project/run {:argv [\"bb\"]})")
          envelope (adapter/run-envelope project-changed-result stub-reference 1)]
      (is (= (:status semantic) (:output/status envelope)))
      (is (= (:process/status semantic)
             (get-in envelope [:output/process :process/status])))
      (is (= (:process/exit semantic)
             (get-in envelope [:output/process :process/exit])))
      (is (nil? (get-in envelope [:run/input :input/coordinate]))))))

;; ---------------------------------------------------------------------------
;; Replay and the invocation index

(deftest a-performed-run-carries-the-environments-invocation-index
  ;; The index is the counter read when the run returns, so each envelope
  ;; is built immediately, while that run is still the latest one.
  (let [environment (stub!)
        first (adapter/envelope-for
               environment (execution/-execute environment {}))
        second (adapter/envelope-for
                environment (execution/-execute environment {}))]
    (is (= 1 (:run/invocation-index first)))
    (is (= 2 (:run/invocation-index second)))
    (is (= 2 (executor/invocation-count environment)))))

(deftest a-replay-restores-the-run-without-moving-the-invocation-index
  ;; execution_test's replay evidence, with the envelope reading the
  ;; numbers off the environment that witnessed it: the recorded run is
  ;; index 1; a receipt-driven reconstruction leaves the counter at 1; the
  ;; replay envelope says exactly that.
  (let [environment (stub!)
        app (session! environment)
        source "(def verification (project/run {:argv [\"bb\" \"check\"]}))"
        recorded (app-runtime/evaluate app source {:transcript :record})
        receipts (:operations recorded)
        performed (adapter/envelope-for environment completed-result)]
    (is (= :ok (:status recorded)) (pr-str recorded))
    (is (= 1 (:run/invocation-index performed)))
    (conforms "run-completed.edn" performed)
    (let [resumed (session! environment)
          replayed (app-runtime/evaluate resumed source
                                         {:transcript :replay
                                          :receipts receipts})]
      (is (= :ok (:status replayed)) (pr-str replayed))
      (is (= 1 (executor/invocation-count environment))
          "replay ran the command a second time")
      (is (= (get-in recorded [:evaluation :value :value/data])
             (get-in replayed [:evaluation :value :value/data]))
          "the restored result differs from the recorded one")
      (conforms "replay-restored.edn"
                (adapter/replay-envelope environment performed)))))

(deftest a-replay-envelope-cannot-describe-a-count-it-never-reached
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot exceed"
                        (spi/replay-envelope 2 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                        (spi/replay-envelope 0 0))))

;; ---------------------------------------------------------------------------
;; The fixtures as a body

(deftest every-fixture-is-a-valid-envelope-with-no-host-paths
  (doseq [file (->> (file-seq (io/file fixture-directory))
                    (map str)
                    (filter #(.endsWith % ".edn"))
                    sort)]
    (testing file
      (let [text (slurp file)]
        (is (spi/envelope? (spi/read-envelope text)))
        (is (not (str/includes? text "/tmp/")) "a host path reached a fixture")
        (is (not (str/includes? text "/home/")) "a host path reached a fixture")))))

(deftest every-fixture-matches-its-golden-sha
  ;; The same check sha256sum -c makes, from inside the suite, so the
  ;; fixtures cannot drift even if nobody runs the tool.
  (doseq [file (->> (file-seq (io/file fixture-directory))
                    (map str)
                    (filter #(.endsWith % ".sha256"))
                    sort)]
    (testing file
      (let [[digest name] (str/split (str/trim (slurp file)) #"  ")]
        (is (= digest (spi/sha-256 (slurp (str fixture-directory "/" name))))
            (str name " drifted from its golden digest"))))))
