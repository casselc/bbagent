(ns bbagent.replay-test
  "Recovery semantics: what resuming a session is entitled to reconstruct.

  A session's computational state is rebuilt by running its own source again.
  That is exact for ordinary Clojure and wrong for anything that touched the
  project: an observation re-run against a changed world answers a different
  question, and an actuation re-run is a second change.  These tests are about
  the boundary between the two -- that the Clojure still runs, that the
  operations inside it do not, and that recovery refuses rather than guesses
  when the two no longer agree."
  (:require [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files OpenOption Path]))

(def ^:private backends [:file :sqlite])

(defn- temp-root [prefix]
  (str (Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- project
  "A fixture project.  Each file exists so a later change to it can be told
   apart from the value the session recorded before the change."
  []
  (let [^Path root (Files/createTempDirectory
                    "bbagent-replay-project"
                    (make-array java.nio.file.attribute.FileAttribute 0))]
    (doseq [[name content] {"a.txt" "alpha"
                            "b.txt" "beta"
                            "notes.txt" "seed"
                            "needle.txt" "the needle is here"}]
      (Files/writeString (.resolve root name) content
                         (make-array OpenOption 0)))
    (str root)))

(defn- write! [project-root name content]
  (spit (io/file project-root name) content))

(defn- session-id-for [prefix backend] (str prefix "-" (clojure.core/name backend)))

(defn- start [state-root project-root session-id backend]
  (session/start! {:state-root state-root
                   :project-root project-root
                   :model-provider (provider/fake [])
                   :system-prompt "base"
                   :session-id session-id
                   :store-backend backend}))

(defn- resume [state-root session-id backend]
  (session/resume! {:state-root state-root
                    :session-id session-id
                    :model-provider (provider/fake [])
                    :system-prompt "base"
                    :store-backend backend}))

(defn- value
  "What an operator evaluation returned, as inert data."
  [result]
  (get-in result [:evaluation :value :value/data]))

(defn- evaluate! [session source]
  (let [result (session/operator-evaluate! session source)]
    (is (= :ok (:status result)) (str "setup form failed: " source))
    result))

(defn- recovery-failure [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo failure
         (when (= :session-recovery-failure (:bbagent/error (ex-data failure)))
           failure))))

(defn- replay-summary [session]
  (->> (session/session-events session)
       (filter #(= :session/resumed (:event/type %)))
       last
       :session/replay))

(defn- rewrite-checkpoint!
  "Appends a checkpoint whose replay forms have been rewritten.

  This is how a divergence is staged.  A session written by the product always
  agrees with its own receipts, so the only honest way to test what recovery
  does when it stops agreeing is to write the disagreement into the durable
  history and resume from it.  It is also exactly the shape of an older
  session: dropping :request/id from a form reproduces what checkpoints
  recorded before receipts existed."
  [state-root session-id backend rewrite]
  (let [event-store (storage/open! state-root backend)]
    (try
      (let [checkpoint (store/latest-checkpoint event-store session-id)]
        (store/append-event!
         event-store session-id
         {:event/type :session/checkpoint
          :checkpoint/reason :replay-test-rewrite
          :session/messages (:session/messages checkpoint)
          :repl/replay-forms (mapv rewrite (:repl/replay-forms checkpoint))}))
      (finally (store/close-store! event-store)))))

;; --- 1, 2, 3: observation ------------------------------------------------

(deftest recovery-reconstructs-observations-rather-than-repeating-them-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-replay-observe")
            project-root (project)
            session-id (session-id-for "observe" backend)
            s (start state-root project-root session-id backend)]
        (evaluate! s "(def pure (+ 20 22))")
        (evaluate! s "(def text (project/read \"a.txt\"))")
        (evaluate! s "(def entries (project/list \".\"))")
        (evaluate! s "(def hits (project/search \"needle\"))")
        (evaluate! s "(def coordinate (project/stat \"a.txt\"))")
        (evaluate! s "(defn shout [s] (str/upper-case s))")
        (session/close! s :test-end)

        ;; The world moves on while the process is stopped.
        (write! project-root "a.txt" "alpha changed on disk")
        (write! project-root "c.txt" "a file the session never saw")
        (write! project-root "needle.txt" "no longer matching")

        (let [resumed (resume state-root session-id backend)]
          (try
            (testing "ordinary Clojure executes normally on replay"
              (is (= 42 (value (evaluate! resumed "pure"))))
              (is (= "ALPHA" (value (evaluate! resumed "(shout \"alpha\")")))
                  "an agent-authored helper is computation, and still replays"))

            (testing "a read resumes with the value the session computed"
              (is (= "alpha" (value (evaluate! resumed "text"))))
              (is (not= (value (evaluate! resumed "text"))
                        (value (evaluate! resumed "(project/read \"a.txt\")")))
                  "which is not what the file says now"))

            (testing "a listing resumes without seeing files added since"
              (let [names (value (evaluate! resumed "(mapv :name entries)"))]
                (is (= ["a.txt" "b.txt" "needle.txt" "notes.txt"] names))
                (is (not (contains? (set names) "c.txt")))))

            (testing "a search resumes with the matches it found"
              (is (= 1 (value (evaluate! resumed "(count hits)"))))
              (is (= 0 (value (evaluate! resumed
                                         "(count (project/search \"needle\"))")))
                  "and the same search now finds nothing"))

            (testing "a stat resumes with the version it observed"
              (is (not= (value (evaluate! resumed "(:digest coordinate)"))
                        (value (evaluate! resumed
                                          "(:digest (project/stat \"a.txt\"))")))))

            (testing "and the session says the reconstruction was exact"
              (let [replay (replay-summary resumed)]
                (is (true? (:exact? replay)))
                (is (= [] (:reobserved replay)))
                (is (= 6 (:forms replay)))
                (is (= 6 (:reconstructed replay)))
                (is (= 0 (:legacy replay)))))
            (finally (session/close! resumed :test-end))))))))

;; --- 4, 5, 6: actuation --------------------------------------------------

(deftest recovery-never-issues-a-recorded-change-again-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-replay-edit")
            project-root (project)
            session-id (session-id-for "edit" backend)
            s (start state-root project-root session-id backend)]
        ;; A helper holding several operations, so recovery has to re-run the
        ;; Clojure around them rather than skipping the form that contains
        ;; them.  The transform appends, so a repeated application would be
        ;; plainly visible in the file rather than idempotent.
        (evaluate! s (str "(defn bump [path]"
                          "  (let [before (project/stat path)"
                          "        text (project/read path)]"
                          "    (project/edit {:path path"
                          "                   :base {:digest (:digest before)}"
                          "                   :content (str text \"!\")})))"))
        (evaluate! s "(def bumped (bump \"notes.txt\"))")
        (evaluate! s (str "(def created (project/edit {:path \"new.txt\" "
                          ":base :absent :content \"first\"}))"))
        (session/close! s :test-end)

        (is (= "seed!" (slurp (io/file project-root "notes.txt"))))
        (is (= "first" (slurp (io/file project-root "new.txt"))))

        (let [resumed (resume state-root session-id backend)]
          (try
            (testing "no write is issued a second time"
              (is (= "seed!" (slurp (io/file project-root "notes.txt")))
                  "a repeated bump would have written seed!!")
              (is (= "first" (slurp (io/file project-root "new.txt")))))

            (testing "the bindings the changes produced are reconstructed"
              (is (= "notes.txt" (value (evaluate! resumed "(:path bumped)"))))
              (is (= 5 (value (evaluate! resumed "(:bytes bumped)"))))
              (is (= 5 (value (evaluate! resumed "(:bytes created)")))))

            (testing "the helper itself is still callable against the world"
              (evaluate! resumed "(bump \"notes.txt\")")
              (is (= "seed!!" (slurp (io/file project-root "notes.txt")))
                  "recovery restored the definition without restoring its effect"))

            (is (true? (:exact? (replay-summary resumed))))
            (finally (session/close! resumed :test-end))))))))

;; --- 7, 8: divergence ----------------------------------------------------

(deftest recovery-fails-closed-when-replay-diverges-test
  (let [project-root (project)
        staged
        (fn [suffix source rewrite]
          (let [state-root (temp-root (str "bbagent-replay-" suffix))
                session-id (str "diverge-" suffix)
                s (start state-root project-root session-id :sqlite)]
            ;; Not evaluate!: one of these cases records a form that failed,
            ;; because a divergence has to be caught even when the status the
            ;; replay reaches is the status the session recorded.
            (session/operator-evaluate! s source)
            (session/close! s :test-end)
            (rewrite-checkpoint! state-root session-id :sqlite rewrite)
            (recovery-failure #(resume state-root session-id :sqlite))))
        reason #(get-in (ex-data %) [:error/data :transcript/error])]

    (testing "a replay that calls the same operation with different arguments"
      (let [failure (staged "args" "(def t (project/read \"a.txt\"))"
                            #(assoc % :source "(def t (project/read \"b.txt\"))"))]
        (is (some? failure) "recovery must not silently succeed")
        (is (= :args-mismatch (reason failure)))))

    (testing "a replay that takes a different branch"
      (let [failure (staged "branch" "(def t (project/read \"a.txt\"))"
                            #(assoc % :source "(def t (project/stat \"a.txt\"))"))]
        (is (some? failure))
        (is (= :operation-mismatch (reason failure)))))

    (testing "a replay that invokes more operations than were recorded"
      (let [failure (staged "extra" "(def t (project/read \"a.txt\"))"
                            #(assoc % :source (str "(do (project/read \"a.txt\")"
                                                   " (project/read \"a.txt\"))")))]
        (is (some? failure))
        (is (= :exhausted (reason failure)))))

    (testing "a transcript with a receipt nothing consumed"
      (let [failure (staged "unconsumed" "(def t (project/read \"a.txt\"))"
                            #(assoc % :source "(def t 1)"))]
        (is (some? failure))
        (is (= :unconsumed (reason failure)))))

    (testing "a divergence that reaches the recorded status anyway"
      ;; The status check alone would have passed this: both forms fail. The
      ;; transcript is what notices that they failed about different things.
      (let [failure (staged "status" "(project/read \"missing.txt\")"
                            #(assoc % :source "(project/read \"absent.txt\")"))]
        (is (some? failure))
        (is (= :args-mismatch (reason failure)))))))

;; --- 9: large results ----------------------------------------------------

(deftest a-large-recorded-result-survives-restart-through-the-object-store-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-replay-blob")
            project-root (project)
            session-id (session-id-for "blob" backend)
            ;; Over the store's externalization threshold, so the recorded
            ;; result is written as a content-addressed object rather than
            ;; inline in the event.
            big (str/join (repeat 20000 "abcd"))
            s (start state-root project-root session-id backend)]
        (write! project-root "big.txt" big)
        (evaluate! s "(def content (project/read \"big.txt\"))")
        (session/close! s :test-end)
        (write! project-root "big.txt" "replaced while the process was stopped")

        (let [resumed (resume state-root session-id backend)]
          (try
            (is (= (count big) (value (evaluate! resumed "(count content)"))))
            (is (= "abcdabcd" (value (evaluate! resumed "(subs content 0 8)"))))
            (is (= (count big)
                   (value (evaluate! resumed
                                     "(count (str/replace content \"x\" \"y\"))")))
                "the whole historical value is present, not a preview of it")
            (is (true? (:exact? (replay-summary resumed))))
            (finally (session/close! resumed :test-end))))))))

;; --- 10: sessions recorded before receipts existed -----------------------

(deftest a-session-without-receipts-replays-as-far-as-it-can-justify-test
  (let [project-root (project)
        ;; A checkpoint form with no :request/id is exactly what A0, A1 and
        ;; A1.1 recorded: there is no result event to find receipts on, so
        ;; recovery has nothing to substitute.
        forget-receipts #(dissoc % :request/id)
        staged (fn [suffix forms]
                 (let [state-root (temp-root (str "bbagent-replay-" suffix))
                       session-id (str "legacy-" suffix)
                       s (start state-root project-root session-id :sqlite)]
                   (doseq [form forms] (evaluate! s form))
                   (session/close! s :test-end)
                   (rewrite-checkpoint! state-root session-id :sqlite
                                        forget-receipts)
                   [state-root session-id]))]

    (testing "a pure historical session still resumes, and exactly"
      (let [[state-root session-id] (staged "pure" ["(def n (* 6 7))"])
            resumed (resume state-root session-id :sqlite)]
        (try
          (is (= 42 (value (evaluate! resumed "n"))))
          (let [replay (replay-summary resumed)]
            (is (true? (:exact? replay)))
            (is (= 1 (:legacy replay)))
            (is (= 0 (:reconstructed replay))))
          (finally (session/close! resumed :test-end)))))

    (testing "a historical observation replays against today's world, and says so"
      (let [[state-root session-id] (staged "read" ["(def t (project/read \"a.txt\"))"])]
        (write! project-root "a.txt" "changed after the session recorded it")
        (let [resumed (resume state-root session-id :sqlite)]
          (try
            (is (= "changed after the session recorded it"
                   (value (evaluate! resumed "t")))
                "there is no receipt, so this is a re-observation")
            (let [replay (replay-summary resumed)]
              (is (false? (:exact? replay))
                  "and the session records that plainly rather than implying history")
              (is (= [:project/read] (:reobserved replay))))
            (finally (session/close! resumed :test-end))))))

    (testing "a historical change is refused rather than made twice"
      (let [[state-root session-id]
            (staged "edit" [(str "(project/edit {:path \"legacy.txt\" "
                                 ":base :absent :content \"once\"})")])
            failure (recovery-failure #(resume state-root session-id :sqlite))]
        (is (some? failure) "recovery must not re-apply an unrecorded change")
        (is (= :actuation-without-transcript
               (get-in (ex-data failure) [:error/data :transcript/error])))
        (is (= "once" (slurp (io/file project-root "legacy.txt")))
            "and the world is left as the session left it")))))

;; --- what the receipts are, and are not ----------------------------------

(deftest receipts-are-durable-recovery-state-not-model-state-test
  (let [state-root (temp-root "bbagent-replay-shape")
        project-root (project)
        session-id "receipt-shape"
        s (start state-root project-root session-id :sqlite)
        returned (evaluate! s "(def text (project/read \"a.txt\"))")]
    (session/close! s :test-end)
    (is (nil? (:operations returned))
        "an evaluation returns what it evaluated, not how it will be replayed")
    (let [event-store (storage/open! state-root :sqlite)]
      (try
        (let [result (->> (store/events event-store session-id)
                          (filter #(= :repl/result (:event/type %)))
                          last)
              receipts (:repl/operations result)]
          (is (nil? (:operations (:repl/result result)))
              "and the journalled result is the same value the model saw")
          (is (= [:project/read] (mapv :operation/id receipts)))
          (is (= #{:project/read} (:effects (first receipts)))
              "each receipt carries the capability's own effect metadata")
          (is (= "alpha" (:result (first receipts))))
          (is (str/starts-with? (:args/digest (first receipts)) "sha256:"))
          (is (str/starts-with? (:result/digest (first receipts)) "sha256:")))
        (finally (store/close-store! event-store))))))

(deftest a-recorded-result-the-journal-could-not-keep-intact-fails-closed-test
  (testing "a result stripped on its way to the journal is refused, not guessed"
    ;; The journal removes entries that look like credentials at any depth,
    ;; which is right for what it stores and lossy for what recovery has to
    ;; reproduce exactly. The result digest is taken before that happens, so a
    ;; value that came back changed stops recovery instead of quietly becoming
    ;; the state the session resumes with.
    (let [state-root (temp-root "bbagent-replay-stripped")
          project-root (project)
          session-id "stripped-result"
          s (start state-root project-root session-id :sqlite)]
      (evaluate! s "(def parsed (data.json/read \"{\\\"token\\\":1,\\\"n\\\":2}\"))")
      (session/close! s :test-end)
      (let [failure (recovery-failure #(resume state-root session-id :sqlite))]
        (is (some? failure))
        (is (= :result-integrity
               (get-in (ex-data failure) [:error/data :transcript/error])))))))

(deftest a-resumed-session-can-be-resumed-again-test
  (let [state-root (temp-root "bbagent-replay-twice")
        project-root (project)
        session-id "resume-twice"
        s (start state-root project-root session-id :sqlite)]
    (evaluate! s "(def text (project/read \"a.txt\"))")
    (evaluate! s (str "(def made (project/edit {:path \"twice.txt\" "
                      ":base :absent :content \"once\"}))"))
    (session/close! s :test-end)
    (let [second-run (resume state-root session-id :sqlite)]
      (evaluate! second-run "(def derived (str text \"-\" (:bytes made)))")
      (session/close! second-run :test-end))
    (write! project-root "a.txt" "changed between the two resumes")
    (let [third-run (resume state-root session-id :sqlite)]
      (try
        (is (= "alpha-4" (value (evaluate! third-run "derived")))
            "state built on a reconstruction reconstructs in its turn")
        (is (= "once" (slurp (io/file project-root "twice.txt")))
            "and the change is still made exactly once")
        (is (true? (:exact? (replay-summary third-run))))
        (finally (session/close! third-run :test-end))))))
