(ns bbagent.orientation-test
  "The orientation preamble must be a projection of the runtime's own authority
   description, never independently maintained prose."
  (:require [bbagent.bb4t :as bb4t]
            [bbagent.orientation :as orientation]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]))

(defn- temp-root [prefix]
  (str (Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- project []
  (let [root (Files/createTempDirectory
              "bbagent-orientation-project"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString (.resolve root "README.md") "fixture"
                       (make-array java.nio.file.OpenOption 0))
    (str root)))

(defn- description [] (:context/description (bb4t/create (project))))

(deftest mode-normalization-test
  (is (= :none (orientation/mode nil)))
  (is (= :none (orientation/mode :none)))
  (is (= :minimal (orientation/mode "minimal")))
  (is (= :generated (orientation/mode :generated)))
  (is (= :grounded (orientation/mode :grounded)))
  (is (= :derived (orientation/mode :derived)))
  (testing "a coordinate written before orientation existed resolves to :none"
    (is (= :none (orientation/mode
                  (get-in {:session/coordinate {:prompt {}}}
                          [:session/coordinate :prompt :orientation])))))
  (testing "an unknown mode is rejected rather than silently ignored"
    (is (thrown? clojure.lang.ExceptionInfo (orientation/mode :verbose)))
    (is (thrown? clojure.lang.ExceptionInfo (orientation/mode 7)))))

(deftest generated-preamble-derives-from-the-context-test
  (let [preamble (orientation/generated-preamble (description))]
    (testing "every projected Var appears, with the runtime's own arglists"
      (is (str/includes? preamble "(project/read relative-path)"))
      (is (str/includes? preamble "(data.json/read json-string)"))
      (is (str/includes? preamble "(data.json/write value)")))
    (testing "the runtime's docstrings are used, not restated"
      (is (str/includes? preamble
                         "Read a UTF-8 file relative to the authorized project root")))
    (testing "the profile comes from the effective context"
      (is (str/includes? preamble ":agent/project-develop")))
    (testing "it names the discovery surface that already exists"
      (is (str/includes? preamble "(apropos \"\")"))
      (is (str/includes? preamble "(doc ")))
    (testing "it stays short enough to prepend to every turn"
      ;; Bounded by construction rather than by this number: at most twelve
      ;; operations, each docstring capped. The number tracks that bound as
      ;; the surface grows and is not itself the guarantee.
      (is (< (count preamble) 2500)))))

(deftest generated-preamble-cannot-invent-authority-test
  (testing "a context projecting nothing produces no preamble"
    (is (nil? (orientation/generated-preamble
               {:context/surface {:projections []}}))))
  (testing "only projected operations can appear"
    (let [preamble (orientation/generated-preamble
                    {:context/effective {:context/profile :test/profile}
                     :context/surface
                     {:projections [{:sci/var 'only.ns/op
                                     :arglists '([x])
                                     :doc "The only operation."}]}})]
      (is (str/includes? preamble "(only.ns/op x)"))
      (is (not (str/includes? preamble "project/read")))
      (is (not (str/includes? preamble "data.json")))))
  (testing "a large surface is bounded rather than dumped"
    (let [many (mapv (fn [n] {:sci/var (symbol "ns" (str "op" n))
                              :arglists '([x])
                              :doc (str "Operation " n)})
                     (range 40))
          preamble (orientation/generated-preamble
                    {:context/surface {:projections many}})]
      (is (str/includes? preamble "and 28 more"))
      (is (str/includes? preamble "(apropos \"\")")
          "the complete list stays reachable through the discovery surface")
      (testing "a truncated list is never described as complete"
        ;; The review found the preamble asserting "exactly these
        ;; operations", "... and 28 more", and "That list is complete" at
        ;; once.  A preamble whose purpose is to stop unsupported claims
        ;; must not make one.
        (is (not (str/includes? preamble "That list is complete"))
            "a truncated surface must not be claimed complete")
        (is (not (str/includes? preamble "exactly these operations"))
            "a truncated surface is not the exact surface")
        (is (str/includes? preamble "partial"))
        (is (str/includes? preamble "these 12 of its 40 operations")))))
  (testing "a surface that fits is still stated as complete"
    (let [preamble (orientation/generated-preamble
                    {:context/surface
                     {:projections [{:sci/var 'only.ns/op
                                     :arglists '([x])
                                     :doc "The only operation."}]}})]
      (is (str/includes? preamble "exactly these operations"))
      (is (str/includes? preamble "That list is complete."))
      (is (not (str/includes? preamble "partial"))))))

(deftest compose-test
  (let [d (description)]
    (testing ":none leaves the base prompt untouched"
      (is (= "base" (orientation/compose "base" :none d))))
    (testing "the base prompt always comes first"
      (doseq [m [:minimal :generated]]
        (is (str/starts-with? (orientation/compose "base" m d) "base\n\n"))))
    (testing ":minimal adds only the discovery instruction"
      (let [out (orientation/compose "base" :minimal d)]
        (is (str/includes? out "(apropos \"\")"))
        (is (not (str/includes? out "project/read"))
            "the minimal variant must not enumerate operations")))
    (testing ":generated enumerates the projected operations"
      (is (str/includes? (orientation/compose "base" :generated d)
                         "(project/read relative-path)")))
    (testing ":grounded is :generated plus a constraint on claims"
      (let [generated (orientation/compose "base" :generated d)
            grounded (orientation/compose "base" :grounded d)]
        (is (str/starts-with? grounded generated)
            "grounding must add to the generated preamble, not replace it")
        (is (str/includes? grounded "cannot"))
        (is (str/includes? grounded "enumerate"))
        (testing "it constrains assertions rather than granting operations"
          (is (not (str/includes? orientation/grounding-constraint "apropos"))))))))

(deftest orientation-is-recorded-as-a-coordinate-test
  (testing "the session envelope records the variant and digests what the model sees"
    (let [state-root (temp-root "bbagent-orientation-coordinate")
          digests
          (into {}
                (for [m [:none :minimal :generated :grounded :derived]]
                  (let [s (session/start! {:state-root state-root
                                           :project-root (project)
                                           :model-provider (provider/fake [])
                                           :system-prompt "base prompt"
                                           :session-id (str "orientation-" (name m))
                                           :store-backend :sqlite
                                           :orientation m})]
                    (try
                      (is (= m (get-in s [:coordinate :prompt :orientation])))
                      [m (get-in s [:coordinate :prompt :system/digest])]
                      (finally (session/close! s :test-end))))))]
      (testing "each variant is a distinct prompt coordinate"
        (is (= 5 (count (set (vals digests))))
            "runs must be distinguishable by their prompt digest")))))

(deftest orientation-does-not-change-authority-test
  (testing "orientation adds no capability to the model Context"
    (let [state-root (temp-root "bbagent-orientation-authority")
          project-root (project)
          surfaces
          (for [m [:none :minimal :generated :grounded :derived]]
            (let [s (session/start! {:state-root state-root
                                     :project-root project-root
                                     :model-provider (provider/fake [])
                                     :system-prompt "base prompt"
                                     :session-id (str "authority-" (name m))
                                     :store-backend :sqlite
                                     :orientation m})]
              (try
                (let [d (get-in s [:bb4t :context/description])]
                  {:spec (:context/spec d)
                   :grants (get-in d [:context/effective :context/grants])
                   :classes (get-in d [:context/surface :projected-class-count])
                   :imports (get-in d [:context/surface :supplied-import-count])})
                (finally (session/close! s :test-end)))))]
      (is (= 1 (count (set surfaces)))
          "every variant must produce an identical authority surface")
      (is (= (bb4t/capabilities bb4t/default-profile)
             (:grants (first surfaces)))
          "orientation projects the granted surface, whatever it is")
      (is (zero? (:classes (first surfaces))))
      (is (zero? (:imports (first surfaces)))))))

(deftest orientation-reaches-the-model-request-test
  (testing "the composed prompt is the system message the provider receives"
    (let [state-root (temp-root "bbagent-orientation-request")
          captured (atom nil)
          recording (provider/fake
                     [(fn [request]
                        (reset! captured request)
                        (provider/fake-response
                         {:action/type :finish :message "done"}))])
          s (session/start! {:state-root state-root
                             :project-root (project)
                             :model-provider recording
                             :system-prompt "base prompt"
                             :store-backend :sqlite
                             :orientation :generated})]
      (try
        (session/add-user-message! s "hello")
        (session/request-model! s)
        (let [system (->> @captured :messages (filter #(= :system (:role %)))
                          first :content)]
          (is (str/starts-with? system "base prompt"))
          (is (str/includes? system "(project/read relative-path)"))
          (is (str/includes? system "(apropos \"\")")))
        (finally (session/close! s :test-end))))))

(deftest orientation-is-durable-in-the-journal-test
  (testing "a run's orientation is recoverable from the durable journal"
    (let [state-root (temp-root "bbagent-orientation-journal")
          session-id "orientation-journal"
          s (session/start! {:state-root state-root
                             :project-root (project)
                             :model-provider (provider/fake [])
                             :system-prompt "base prompt"
                             :session-id session-id
                             :store-backend :sqlite
                             :orientation :generated})]
      (session/close! s :test-end)
      (let [root (storage/open! state-root :sqlite)]
        (try
          (let [started (store/first-event root session-id :session/started)]
            (is (= :generated
                   (get-in started [:session/coordinate :prompt :orientation]))
                "an experiment run must be attributable from its journal alone"))
          (finally (store/close-store! root)))))))

(deftest resume-keeps-the-session-orientation-test
  (testing "a resumed session keeps the orientation it was started with"
    ;; Found in review: resuming without repeating the flag silently
    ;; returned the model to the unoriented prompt, in a session whose
    ;; conversation history had been produced under orientation.
    (let [state-root (temp-root "bbagent-orientation-resume")
          project-root (project)
          session-id "orientation-resume"
          started (session/start! {:state-root state-root
                                   :project-root project-root
                                   :model-provider (provider/fake [])
                                   :system-prompt "base prompt"
                                   :session-id session-id
                                   :store-backend :sqlite
                                   :orientation :grounded})]
      (is (= :grounded (get-in started [:coordinate :prompt :orientation])))
      (session/close! started :test-end)
      (let [resumed (session/resume! {:state-root state-root
                                      :session-id session-id
                                      :model-provider (provider/fake [])
                                      :system-prompt "base prompt"
                                      :store-backend :sqlite})]
        (try
          (is (= :grounded
                 (get-in resumed [:coordinate :prompt :orientation]))
              "the resumed run records the inherited orientation")
          (is (str/includes? (:system-prompt resumed)
                             orientation/grounding-constraint)
              "the model keeps receiving the grounding constraint")
          (is (= (:system-prompt started) (:system-prompt resumed))
              "the same prompt reaches the model across the resume")
          (finally (session/close! resumed :test-end)))))))

(deftest resume-orientation-override-applies-to-that-run-test
  (testing "an explicit orientation overrides the inherited one"
    (let [state-root (temp-root "bbagent-orientation-override")
          project-root (project)
          session-id "orientation-override"
          started (session/start! {:state-root state-root
                                   :project-root project-root
                                   :model-provider (provider/fake [])
                                   :system-prompt "base prompt"
                                   :session-id session-id
                                   :store-backend :sqlite
                                   :orientation :grounded})]
      (session/close! started :test-end)
      (let [resumed (session/resume! {:state-root state-root
                                      :session-id session-id
                                      :model-provider (provider/fake [])
                                      :system-prompt "base prompt"
                                      :store-backend :sqlite
                                      :orientation :none})]
        (try
          (is (= :none (get-in resumed [:coordinate :prompt :orientation]))
              "an explicit override is honoured and recorded")
          (is (= "base prompt" (:system-prompt resumed)))
          (finally (session/close! resumed :test-end)))))))

(deftest resume-does-not-orient-an-unoriented-session-test
  (testing "the default does not reach back into a session that recorded :none"
    ;; A session recorded :none either because it predates orientation, whose
    ;; coordinate resolves to :none through mode, or because its operator
    ;; chose :none.  Either way its history was produced unoriented and
    ;; changing the default for new sessions must not change it mid-session.
    (let [state-root (temp-root "bbagent-orientation-legacy")
          project-root (project)
          session-id "orientation-legacy"
          started (session/start! {:state-root state-root
                                   :project-root project-root
                                   :model-provider (provider/fake [])
                                   :system-prompt "base prompt"
                                   :session-id session-id
                                   :store-backend :sqlite
                                   :orientation :none})]
      (session/close! started :test-end)
      (let [resumed (session/resume! {:state-root state-root
                                      :session-id session-id
                                      :model-provider (provider/fake [])
                                      :system-prompt "base prompt"
                                      :store-backend :sqlite})]
        (try
          (is (= :none (get-in resumed [:coordinate :prompt :orientation])))
          (is (= "base prompt" (:system-prompt resumed)))
          (finally (session/close! resumed :test-end)))))))

(deftest new-sessions-default-to-derived-test
  (testing "a new session is oriented against its actual surface"
    ;; :grounded is what A1.1 measured, but it states limits as prose and
    ;; A2's surface can enumerate, so :grounded would now deny a capability
    ;; the same prompt offers. :derived generates its claims instead.
    (let [state-root (temp-root "bbagent-orientation-default")
          s (session/start! {:state-root state-root
                             :project-root (project)
                             :model-provider (provider/fake [])
                             :system-prompt "base prompt"
                             :store-backend :sqlite})]
      (try
        (is (= :derived (get-in s [:coordinate :prompt :orientation])))
        (is (str/includes? (:system-prompt s) orientation/derived-constraint))
        (testing "it offers the enumerating operation and denies nothing"
          (is (str/includes? (:system-prompt s) "(project/list relative-path)"))
          (is (not (str/includes? (:system-prompt s) "cannot enumerate"))))
        (testing "the default still adds no authority beyond its profile"
          (let [d (get-in s [:bb4t :context/description])]
            (is (= (bb4t/capabilities bb4t/default-profile)
                   (get-in d [:context/effective :context/grants])))
            (is (zero? (get-in d [:context/surface :projected-class-count])))
            (is (zero? (get-in d [:context/surface :supplied-import-count])))))
        (finally (session/close! s :test-end))))))

(def ^:private a2-shaped-context
  "A surface shaped like the one A2 will create: the enumerating capability
   :grounded's prose denies is actually granted."
  {:context/effective {:context/profile :agent/project-rw}
   :context/surface
   {:projections
    [{:sci/var 'project/read :arglists '([relative-path])
      :effects #{:project/read}
      :doc "Read a UTF-8 file relative to the authorized project root."}
     {:sci/var 'project/list :arglists '([relative-path])
      :effects #{:project/list}
      :doc "List entries under a directory relative to the project root."}]}})

(deftest grounded-goes-stale-when-a-capability-is-granted-test
  (testing "the frozen variant contradicts its own operation list in A2"
    ;; Not a defect in :grounded's measured result, which stands. It is the
    ;; reason :grounded cannot be the variant A2 ships: its prose states an
    ;; absence, and an absence is falsified by a grant.
    (let [out (orientation/compose "base" :grounded a2-shaped-context)]
      (is (str/includes? out "(project/list relative-path)")
          "the generated list correctly offers the enumerating operation")
      (is (str/includes? out "You have no file listing")
          "while the frozen prose simultaneously denies it")
      (is (str/includes? out "You cannot enumerate a directory")
          "and instructs the model not to use what it was just granted"))))

(deftest derived-does-not-go-stale-test
  (testing "the derived variant states closure, so a grant cannot falsify it"
    (let [out (orientation/compose "base" :derived a2-shaped-context)]
      (is (str/includes? out "(project/list relative-path)"))
      (testing "it denies nothing the surface grants"
        (doseq [stale ["You have no file listing"
                       "You cannot enumerate a directory"
                       "never say what a project contains"
                       "say that you cannot enumerate"]]
          (is (not (str/includes? out stale))
              (str "derived orientation must not assert: " stale))))
      (testing "it still closes the surface"
        (is (str/includes? out "your whole authority over this project"))
        (is (str/includes? out "Anything not in it is unavailable to you")))
      (testing "it still constrains claims to what operations returned"
        (is (str/includes? out "Only state what your operations actually returned"))
        (is (str/includes? out "is not evidence about the world"))))))

(deftest derived-names-no-capability-test
  (testing "nothing in the derived text names a capability, present or absent"
    ;; The generated operation list is derived and may name operations. The
    ;; prose around it may not, or it becomes the parallel prose this
    ;; namespace exists to avoid.
    (let [prose (str orientation/derived-constraint)]
      (doseq [noun ["file listing" "search" "shell" "process" "network"
                    "host API" "directory" "enumerate" "edit" "project/"]]
        (is (not (str/includes? (str/lower-case prose)
                                (str/lower-case noun)))
            (str "the derived constraint must not name: " noun))))))

(deftest derived-closure-counts-the-real-surface-test
  (testing "the closure statement counts what is actually projected"
    (is (str/includes? (orientation/derived-preamble a2-shaped-context)
                       "those 2 operations are your whole authority"))
    (let [many (mapv (fn [n] {:sci/var (symbol "ns" (str "op" n))
                              :arglists '([x])
                              :doc (str "Operation " n)})
                     (range 40))
          out (orientation/derived-preamble
               {:context/surface {:projections many}})]
      (testing "a truncated surface is closed over the true count, not the shown one"
        (is (str/includes? out "returns all 40"))
        (is (not (str/includes? out "That list is complete")))))))

(deftest cli-shaped-nil-options-do-not-unorient-a-session-test
  (testing "an absent flag means unselected, not :none"
    ;; The CLI passes every option key with a nil value when its flag is
    ;; absent, so destructuring defaults never fire on that path. Before this
    ;; was handled, every CLI session ran unoriented while direct API callers
    ;; got the default, and resume discarded the inherited orientation.
    (let [state-root (temp-root "bbagent-orientation-cli")
          session-id "orientation-cli"
          started (session/start! {:state-root state-root
                                   :project-root (project)
                                   :model-provider (provider/fake [])
                                   :system-prompt "base prompt"
                                   :session-id session-id
                                   :store-backend :sqlite
                                   :orientation nil
                                   :profile nil})]
      (is (= :derived (get-in started [:coordinate :prompt :orientation])))
      (is (= bb4t/default-profile
             (get-in started [:coordinate :context :profile])))
      (session/close! started :test-end)
      (let [resumed (session/resume! {:state-root state-root
                                      :session-id session-id
                                      :model-provider (provider/fake [])
                                      :system-prompt "base prompt"
                                      :store-backend :sqlite
                                      :orientation nil
                                      :profile nil})]
        (try
          (is (= :derived (get-in resumed [:coordinate :prompt :orientation]))
              "a nil flag must not defeat inheritance")
          (is (= bb4t/default-profile
                 (get-in resumed [:coordinate :context :profile])))
          (finally (session/close! resumed :test-end)))))))

(deftest resume-keeps-the-frozen-profile-test
  (testing "an A0-era session is never resumed into a wider surface"
    ;; A replayed form that failed because a capability was absent would
    ;; succeed under a wider profile, and recovery would fail its own
    ;; status-equivalence check.
    (let [state-root (temp-root "bbagent-profile-resume")
          project-root (project)
          session-id "profile-resume"
          started (session/start! {:state-root state-root
                                   :project-root project-root
                                   :model-provider (provider/fake [])
                                   :system-prompt "base prompt"
                                   :session-id session-id
                                   :store-backend :sqlite
                                   :profile :agent/project-read})]
      (is (= :agent/project-read
             (get-in started [:coordinate :context :profile])))
      (session/close! started :test-end)
      (let [resumed (session/resume! {:state-root state-root
                                      :session-id session-id
                                      :model-provider (provider/fake [])
                                      :system-prompt "base prompt"
                                      :store-backend :sqlite})]
        (try
          (is (= :agent/project-read
                 (get-in resumed [:coordinate :context :profile])))
          (is (= #{:data/json-read :data/json-write :project/read}
                 (get-in resumed [:bb4t :context/description
                                  :context/effective :context/grants]))
              "the resumed session keeps the surface its history was made on")
          (finally (session/close! resumed :test-end)))))))
