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
      (is (str/includes? preamble ":agent/project-read")))
    (testing "it names the discovery surface that already exists"
      (is (str/includes? preamble "(apropos \"\")"))
      (is (str/includes? preamble "(doc ")))
    (testing "it stays short enough to prepend to every turn"
      (is (< (count preamble) 1200)))))

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
                (for [m [:none :minimal :generated :grounded]]
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
        (is (= 4 (count (set (vals digests))))
            "runs must be distinguishable by their prompt digest")))))

(deftest orientation-does-not-change-authority-test
  (testing "orientation adds no capability to the model Context"
    (let [state-root (temp-root "bbagent-orientation-authority")
          project-root (project)
          surfaces
          (for [m [:none :minimal :generated :grounded]]
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
      (is (= #{:project/read :data/json-write :data/json-read}
             (:grants (first surfaces))))
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

(deftest resume-without-recorded-orientation-is-none-test
  (testing "a session started before orientation existed resumes unoriented"
    (let [state-root (temp-root "bbagent-orientation-legacy")
          project-root (project)
          session-id "orientation-legacy"
          started (session/start! {:state-root state-root
                                   :project-root project-root
                                   :model-provider (provider/fake [])
                                   :system-prompt "base prompt"
                                   :session-id session-id
                                   :store-backend :sqlite})]
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
