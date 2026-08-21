(ns bbagent.tui-test
  "Deterministic TUI tests.

  The reducer, the projections, and the renderer are pure, so all of this
  runs without a terminal.  The agent integration drives the same worker the
  TUI uses, against a fake provider."
  (:require [bbagent.agent :as agent]
            [bbagent.bb4t :as bb4t]
            [bbagent.errors :as errors]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.store :as store]
            [bbagent.tui.command :as command]
            [bbagent.tui.render :as render]
            [bbagent.tui.state :as state]
            [bbagent.tui.viewmodel :as vm]
            [charm.message :as msg]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]))

(def ^:private ansi-pattern (re-pattern (str (char 27) "\\[[0-9;]*m")))

(defn- plain [line] (str/replace line ansi-pattern ""))

(defn- error-category [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo failure
         (:bbagent/error (ex-data failure)))))

(defn- temp-root [prefix]
  (str (Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- project []
  (let [root (Files/createTempDirectory
              "bbagent-tui-project"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString (.resolve root "README.md") "A tiny fixture project."
                       (make-array java.nio.file.OpenOption 0))
    (str root)))

;; ---------------------------------------------------------------------------
;; View-model projection
;; ---------------------------------------------------------------------------

(deftest conversation-projection-test
  (testing "roles project onto distinguishable turn kinds"
    (let [turns (vm/conversation
                 [{:role :system :content "prompt"}
                  {:role :user :content "hello"}
                  {:role :assistant :content nil
                   :actions [{:action/id "act-1234567890"
                              :action/value {:action/type :repl/eval
                                             :source "(+ 1 2)"}}]}
                  {:role :tool :action/id "act-1234567890" :content {:status :ok}}
                  {:role :assistant :content "done"}])]
      (is (= [:human :agent-action :tool-result :agent-final]
             (mapv :turn/kind turns))
          "the system prompt is not a conversation turn")
      (is (= "hello" (:turn/text (first turns))))
      (is (= "act-1234" (:turn/action-id-short (second turns)))))))

(deftest conversation-truncation-test
  (testing "a huge structured result is summarized, not dumped"
    (let [big (vec (range 5000))
          [turn] (vm/conversation [{:role :tool :action/id "a" :content big}])]
      (is (:turn/truncated? turn))
      (is (< (count (:turn/text turn)) 300))
      (is (> (:turn/full-characters turn) 300)
          "the complete size stays visible so the operator knows to inspect"))))

(deftest capability-projection-is-not-hardcoded-test
  (testing "the capability view derives entirely from context metadata"
    (let [description
          {:context/spec {:requested-capabilities #{:cap/b :cap/a}
                          :authorized-capabilities #{:cap/a}}
           :context/effective {:context/profile :test/profile
                               :context/grants #{:cap/a}
                               :context/limits {:some/limit 5}
                               :context/resources {:project {:resource/id :r}}}
           :context/surface {:projections [{:capability/id :cap/a
                                            :operation/id :ns/op
                                            :sci/var 'some.ns/op
                                            :effects #{:read}
                                            :doc "d"
                                            :arglists '([x])}]
                             :projected-class-count 0
                             :supplied-import-count 0
                             :total-projected-var-count 3}}
          projected (vm/capabilities description)]
      (is (= :test/profile (:profile projected)))
      (is (= [:cap/a] (:effective-grants projected)))
      (is (= ["some.ns/op"] (:vars projected)))
      (is (= ["some.ns"] (:namespaces projected)))
      (is (= 0 (:projected-class-count projected)))
      (testing "a capability the runtime does not grant cannot appear"
        (is (not (contains? (set (:effective-grants projected)) :cap/b)))))))

(deftest real-context-capability-projection-test
  (testing "the projection works on the actual bounded context"
    (let [runtime (bb4t/create (project))
          projected (vm/capabilities (:context/description runtime))]
      (is (= :agent/project-survey (:profile projected)))
      (is (= [:data/json-read :data/json-write :project/list :project/read
              :project/search]
             (:effective-grants projected)))
      (is (= ["data.json/read" "data.json/write" "project/list" "project/read"
              "project/search"]
             (:vars projected)))
      (is (zero? (:projected-class-count projected)))
      (is (zero? (:supplied-import-count projected))))))

(deftest header-hides-secrets-test
  (testing "the header carries orientation data and no secret"
    (let [header (vm/header
                  {:session {:session-id "session-abcdefgh-1"
                             :run-id "run-abcdefgh-1"
                             :project {:project/root "/tmp/p"}
                             :bb4t {:context/description
                                    {:context/coordinate
                                     "sha256:0123456789abcdef0123"}}}
                   :provider-description {:model "m" :provider :openai
                                          :endpoint "https://example"}
                   :store-backend :sqlite
                   :activity :idle})]
      (is (= "session-" (:session/id-short header)))
      (is (= "session-abcdefgh-1" (:session/id header))
          "the complete id stays inspectable")
      (is (= "sha256:0123456789ab" (:context/coordinate-short header)))
      (is (= :sqlite (:store/backend header)))
      (is (not (str/includes? (pr-str header) "api-key"))))))

;; ---------------------------------------------------------------------------
;; Incremental events
;; ---------------------------------------------------------------------------

(deftest incremental-event-window-test
  (testing "initial view plus events-after cursor equals the updated model"
    (let [initial (vm/append-events {:rows [] :cursor nil}
                                    [{:event/seq 1 :event/id "e1"
                                      :event/type :session/started}
                                     {:event/seq 2 :event/id "e2"
                                      :event/type :user/message}]
                                    10)]
      (is (= [1 2] (mapv :event/seq (:rows initial))))
      (is (= "e2" (:cursor initial)) "the cursor is what events-after consumes")
      (let [next-window (vm/append-events initial
                                          [{:event/seq 3 :event/id "e3"
                                            :event/type :model/request}]
                                          10)]
        (is (= [1 2 3] (mapv :event/seq (:rows next-window))))
        (is (= "e3" (:cursor next-window)))
        (is (= 1 (:added next-window))
            "only the new event was appended; nothing was reloaded"))))
  (testing "the window is bounded and drops the oldest rows"
    (let [window (reduce (fn [acc n]
                           (vm/append-events acc [{:event/seq n
                                                   :event/id (str "e" n)
                                                   :event/type :x}] 5))
                         {:rows [] :cursor nil}
                         (range 1 12))]
      (is (= 5 (count (:rows window))))
      (is (= [7 8 9 10 11] (mapv :event/seq (:rows window))))
      (is (= "e11" (:cursor window))))))

(deftest event-row-keeps-structured-value-test
  (testing "detail inspection works on data, not on a rendered string"
    (let [event {:event/seq 4 :event/id "e4" :event/type :model/response
                 :request/id "req-abcdefgh-1" :response/status :ok
                 :model/response {:usage {:tokens 12}}}
          row (vm/event-row event)]
      (is (= :ok (:event/status row)))
      (is (= {:tokens 12} (:event/usage row)))
      (is (= "req-abcd" (:request/id-short row)))
      (is (= event (:event/value row))))))

;; ---------------------------------------------------------------------------
;; Reducer
;; ---------------------------------------------------------------------------

(defn- press
  ([state key] (press state key nil))
  ([state key mods]
   (state/handle-key state (merge {:type :key-press :key key
                                   :alt false :ctrl false :shift false}
                                  mods))))

(deftest focus-cycles-test
  (let [s0 (state/initial {})]
    (is (= :input (:focus s0)))
    (let [s1 (first (press s0 :tab))]
      (is (= :conversation (:focus s1)))
      (is (= :events (:focus (first (press s1 :tab)))))
      (testing "shift-tab cycles backward when the terminal reports it"
        (is (= :input (:focus (first (press s1 :tab {:shift true})))))))))

(deftest space-is-typed-not-swallowed-test
  (testing "a space arrives as a rune and must reach the buffer"
    (let [typed (reduce (fn [st c] (first (press st c))) (state/initial {})
                        ["(" "+" " " "1" " " "2" ")"])]
      (is (= "(+ 1 2)" (get-in typed [:input :buffer]))
          "dropping spaces silently corrupts every multi-token REPL form")))
  (testing "charm's :space key form also inserts a space"
    (let [typed (-> (state/initial {}) (press "a") first
                    (press :space) first (press "b") first)]
      (is (= "a b" (get-in typed [:input :buffer]))))))

(deftest input-editing-test
  (let [typed (reduce (fn [s c] (first (press s c))) (state/initial {})
                      ["h" "i"])]
    (is (= "hi" (get-in typed [:input :buffer])))
    (is (= 2 (get-in typed [:input :cursor])))
    (testing "backspace deletes before the cursor"
      (is (= "h" (get-in (first (press typed :backspace)) [:input :buffer]))))
    (testing "left then backspace deletes the earlier character"
      (let [moved (first (press typed :left))]
        (is (= 1 (get-in moved [:input :cursor])))
        (is (= "i" (get-in (first (press moved :backspace)) [:input :buffer])))))
    (testing "escape clears the line"
      (is (= "" (get-in (first (press typed :escape)) [:input :buffer]))))))

(deftest submit-emits-a-command-not-an-effect-test
  (testing "chat mode submits a session message command"
    (let [typed (reduce (fn [s c] (first (press s c))) (state/initial {})
                        ["h" "i"])
          [after commands] (press typed :enter)]
      (is (= [{:command/type :session/submit-message :text "hi"}] commands))
      (is (= :waiting-for-model (:activity after)))
      (is (= "" (get-in after [:input :buffer])))
      (is (= ["hi"] (get-in after [:input :history])))))
  (testing "repl mode submits an operator evaluation command"
    (let [repl (first (press (state/initial {}) "t" {:ctrl true}))
          typed (reduce (fn [s c] (first (press s c))) repl ["(" ")"])
          [_ commands] (press typed :enter)]
      (is (= :repl (:input/mode repl)))
      (is (= [{:command/type :operator/repl-eval :source "()"}] commands))))
  (testing "a blank line submits nothing"
    (is (nil? (second (press (state/initial {}) :enter))))))

(deftest input-history-test
  (let [after-a (first (press (first (press (state/initial {}) "a")) :enter))
        after-b (first (press (first (press after-a "b")) :enter))]
    (is (= ["a" "b"] (get-in after-b [:input :history])))
    (let [older (first (press after-b :up))]
      (is (= "b" (get-in older [:input :buffer])))
      (is (= "a" (get-in (first (press older :up)) [:input :buffer]))))))

(deftest quit-is-a-command-test
  (testing "quit checkpoints through the application seam"
    (let [[after commands] (press (state/initial {}) "q" {:ctrl true})]
      (is (:quit? after))
      (is (= [{:command/type :session/checkpoint-and-quit}] commands)))))

(deftest ctrl-c-only-clears-input-test
  (testing "Ctrl-C cancels the input line and nothing else"
    (let [typed (reduce (fn [s c] (first (press s c))) (state/initial {}) ["x"])
          [after commands] (press typed "c" {:ctrl true})]
      (is (= "" (get-in after [:input :buffer])))
      (is (nil? commands) "no cancellation command is invented")
      (is (not (:quit? after))))))

(deftest scroll-and-resize-test
  (let [focused (first (press (state/initial {:cols 100 :rows 40}) :tab))]
    (is (= :conversation (:focus focused)))
    (let [scrolled (first (press focused :page-up))]
      (is (pos? (get-in scrolled [:scroll :conversation])))
      (testing "scrolling never goes negative"
        (let [back (-> scrolled (press :page-down) first
                       (press :page-down) first)]
          (is (zero? (get-in back [:scroll :conversation]))))))
    (testing "resize updates only view state"
      (let [resized (state/resize focused 120 50)]
        (is (= {:cols 120 :rows 50} (:size resized)))))))

(deftest event-selection-and-detail-test
  (let [with-events (state/handle-result
                     (state/initial {})
                     {:type :bbagent/events
                      :events [{:event/seq 1 :event/id "e1" :event/type :a}
                               {:event/seq 2 :event/id "e2" :event/type :b}]})
        on-events (-> with-events (press :tab) first (press :tab) first)]
    (is (= :events (:focus on-events)))
    (let [selected (first (press on-events :up))]
      (is (= 1 (get-in selected [:selected :event])))
      (is (= :b (:event/type (state/selected-event
                              (first (press selected :down))))))
      (let [detail (first (press selected :enter))]
        (is (= :event-detail (:modal detail)))
        (testing "escape closes the modal"
          (is (nil? (:modal (first (press detail :escape))))))))))

(deftest error-display-does-not-kill-the-view-test
  (let [errored (state/handle-result
                 (state/initial {})
                 {:type :bbagent/error
                  :error (vm/error-view {:category :bb4t-authorization-denial
                                         :message "denied"
                                         :data {:capability/id :x}})})]
    (is (= :failed (:activity errored)))
    (is (= "authorization denied" (get-in errored [:error :error/label])))
    (is (get-in errored [:error :error/inspectable?]))
    (testing "the concise message carries no stack trace"
      (is (not (str/includes? (get-in errored [:error :error/message]) "at "))))))

(deftest every-error-category-has-a-label-test
  (doseq [category errors/categories]
    (is (not= "error" (get vm/error-labels category "error"))
        (str "category " category " needs an operator-facing label"))))

;; ---------------------------------------------------------------------------
;; Rendering
;; ---------------------------------------------------------------------------

(deftest render-is-pure-and-bounded-test
  (let [view (-> (state/initial {:cols 100 :rows 30 :store-backend :sqlite})
                 (state/handle-result
                  {:type :bbagent/conversation
                   :conversation (vm/conversation
                                  [{:role :user :content "hello"}])})
                 (state/handle-result
                  {:type :bbagent/events
                   :events [{:event/seq 1 :event/id "e1"
                             :event/type :session/started}]}))
        out (render/render view)]
    (is (= out (render/render view)) "same state renders the same string")
    (is (str/includes? out "bbagent"))
    (is (str/includes? out "Conversation"))
    (is (str/includes? out "Recent Events"))
    (testing "no rendered line exceeds the terminal width"
      (doseq [line (str/split-lines out)]
        (is (<= (count (plain line)) 100)
            (str "line too wide: " (pr-str (plain line))))))))

(deftest render-survives-small-terminals-test
  (doseq [[cols rows] [[40 12] [80 24] [200 60]]]
    (let [out (render/render (state/initial {:cols cols :rows rows}))]
      (is (string? out))
      (doseq [line (str/split-lines out)]
        (is (<= (count (plain line)) (max 40 cols))
            (str cols "x" rows " produced an overwide line"))))))

;; ---------------------------------------------------------------------------
;; Agent integration through the worker
;; ---------------------------------------------------------------------------

(defn- drain
  "Collects worker messages until predicate is satisfied or time runs out."
  [worker done? timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [acc []]
      (if (or (done? acc) (> (System/currentTimeMillis) deadline))
        acc
        (if-let [m (command/poll-message! worker 100)]
          (recur (conj acc m))
          (recur acc))))))

(deftest fake-provider-turn-through-tui-seam-test
  (testing "human input reaches AgentSession and the TUI observes the result"
    (let [state-root (temp-root "bbagent-tui-agent")
          model (provider/fake
                 [(provider/fake-response
                   {:action/type :repl/eval
                    :source "(project/read \"README.md\")"})
                  (provider/fake-response
                   {:action/type :finish :message "explained"})])
          agent-session (session/start! {:state-root state-root
                                         :project-root (project)
                                         :model-provider model
                                         :system-prompt "test prompt"
                                         :store-backend :sqlite})
          worker (command/start-worker! {:session-atom (atom agent-session)
                                         :store-backend :sqlite
                                         :state-root state-root})]
      (try
        (command/submit! worker {:command/type :session/submit-message
                                 :text "explain the project"})
        (let [messages (drain worker
                              #(some (fn [m] (= :bbagent/turn-complete (:type m))) %)
                              20000)
              types (set (map :type messages))]
          (is (contains? types :bbagent/turn-complete)
              (str "no turn completion; saw " (pr-str types)))
          (is (contains? types :bbagent/conversation))
          (is (contains? types :bbagent/events))
          (testing "the view receives the durable events incrementally"
            (let [events (mapcat :events
                                 (filter #(= :bbagent/events (:type %)) messages))]
              (is (some #(= :user/message (:event/type %)) events))
              (is (some #(= :repl/result (:event/type %)) events))))
          (testing "the conversation projection distinguishes the turn kinds"
            (let [convo (:conversation (last (filter #(= :bbagent/conversation
                                                         (:type %)) messages)))]
              (is (contains? (set (map :turn/kind convo)) :human))
              (is (contains? (set (map :turn/kind convo)) :agent-final)))))
        (finally
          (command/shutdown! worker)
          (session/close! agent-session :test-end))))))

(deftest worker-reports-errors-as-domain-messages-test
  (testing "a failing command never escapes as a fatal charm error"
    (let [state-root (temp-root "bbagent-tui-error")
          agent-session (session/start! {:state-root state-root
                                         :project-root (project)
                                         :model-provider (provider/fake [])
                                         :system-prompt "test prompt"
                                         :store-backend :sqlite})
          worker (command/start-worker! {:session-atom (atom agent-session)
                                         :store-backend :sqlite
                                         :state-root state-root})]
      (try
        (command/submit! worker {:command/type :nonexistent/command})
        (let [messages (drain worker
                              #(some (fn [m] (= :bbagent/error (:type m))) %)
                              10000)
              error (first (filter #(= :bbagent/error (:type %)) messages))]
          (is (some? error))
          (is (= :bbagent/error (:type error))
              "errors arrive as domain messages, not charm's fatal :error"))
        (finally
          (command/shutdown! worker)
          (session/close! agent-session :test-end))))))

(deftest session-browser-reducer-test
  (testing "the browser selects and resumes through a command"
    (let [listed (state/handle-result (state/initial {:store-backend :sqlite})
                                      {:type :bbagent/sessions
                                       :backend :sqlite
                                       :sessions ["s-a" "s-b"]})]
      (is (= :sessions (:modal listed)))
      (is (= "s-a" (get-in listed [:selected :session])))
      (let [moved (first (press listed :down))]
        (is (= "s-b" (get-in moved [:selected :session])))
        (let [[after commands] (press moved :enter)]
          (is (nil? (:modal after)))
          (is (= [{:command/type :session/resume
                   :session-id "s-b"
                   :backend :sqlite}] commands))
          (is (= :resuming (:activity after)))))
      (testing "selecting the current session resumes nothing"
        (let [current (assoc listed :session/id "s-a")
              [after commands] (press current :enter)]
          (is (nil? commands))
          (is (nil? (:modal after)))))))
  (testing "switching sessions clears view state derived from the old one"
    (let [switched (-> (state/initial {})
                       (state/handle-result
                        {:type :bbagent/events
                         :events [{:event/seq 1 :event/id "e1" :event/type :a}]})
                       (state/handle-result
                        {:type :bbagent/session-switched :session-id "s-new"}))]
      (is (= "s-new" (:session/id switched)))
      (is (= [] (get-in switched [:events :rows])))
      (is (nil? (get-in switched [:events :cursor]))
          "the tail cursor must not leak across sessions")
      (is (= [] (:conversation switched))))))

(deftest session-list-and-resume-through-worker-test
  (testing "list and resume run through the ordinary application seams"
    (let [state-root (temp-root "bbagent-tui-sessions")
          project-root (project)
          make (fn [id]
                 (let [s (session/start! {:state-root state-root
                                          :project-root project-root
                                          :model-provider (provider/fake [])
                                          :system-prompt "test prompt"
                                          :session-id id
                                          :store-backend :sqlite})]
                   (session/close! s :test-end)
                   id))
          _ (make "tui-session-one")
          _ (make "tui-session-two")
          live (session/start! {:state-root state-root
                                :project-root project-root
                                :model-provider (provider/fake [])
                                :system-prompt "test prompt"
                                :session-id "tui-session-live"
                                :store-backend :sqlite})
          session-atom (atom live)
          worker (command/start-worker! {:session-atom session-atom
                                         :store-backend :sqlite
                                         :state-root state-root})]
      (try
        (command/submit! worker {:command/type :sessions/list :backend :sqlite})
        (let [listed (first (filter #(= :bbagent/sessions (:type %))
                                    (drain worker
                                           #(some (fn [m] (= :bbagent/sessions
                                                             (:type m))) %)
                                           10000)))]
          (is (= ["tui-session-live" "tui-session-one" "tui-session-two"]
                 (:sessions listed))))
        (command/submit! worker {:command/type :session/resume
                                 :session-id "tui-session-one"
                                 :backend :sqlite})
        (let [switched (first (filter #(= :bbagent/session-switched (:type %))
                                      (drain worker
                                             #(some (fn [m]
                                                      (= :bbagent/session-switched
                                                         (:type m))) %)
                                             20000)))]
          (is (= "tui-session-one" (:session-id switched)))
          (is (= "tui-session-one" (:session-id @session-atom))
              "the live session behind the view was swapped"))
        (finally
          (command/shutdown! worker)
          (session/close! @session-atom :test-end))))))

(deftest operator-state-survives-resume-test
  (testing "an operator definition the model later uses must reconstruct"
    (doseq [backend [:file :sqlite]]
      (testing (str "backend " (name backend))
        (let [state-root (temp-root (str "bbagent-operator-resume-" (name backend)))
              project-root (project)
              session-id (str "operator-resume-" (name backend))
              first-session (session/start! {:state-root state-root
                                             :project-root project-root
                                             :model-provider (provider/fake [])
                                             :system-prompt "test prompt"
                                             :session-id session-id
                                             :store-backend backend})]
          (is (= :ok (:status (session/operator-evaluate!
                               first-session "(def operator-value 41)"))))
          (session/close! first-session :test-end)

          ;; A later agent form reads the operator definition. Its :ok status
          ;; becomes a durable replay expectation, so if the operator form did
          ;; not reconstruct, the next resume would fail closed.
          (let [second-session
                (session/resume! {:state-root state-root
                                  :session-id session-id
                                  :model-provider
                                  (provider/fake
                                   [(provider/fake-response
                                     {:action/type :repl/eval
                                      :source "(+ operator-value 1)"})
                                    (provider/fake-response
                                     {:action/type :finish :message "42"})])
                                  :system-prompt "test prompt"
                                  :store-backend backend})]
            (is (= "42" (agent/turn! second-session "add one")))
            (session/close! second-session :test-end))

          (let [third-session (session/resume! {:state-root state-root
                                                :session-id session-id
                                                :model-provider (provider/fake [])
                                                :system-prompt "test prompt"
                                                :store-backend backend})]
            (testing "the operator value is reconstructed in the fresh context"
              (is (= 41 (get-in (session/operator-evaluate!
                                 third-session "operator-value")
                                [:evaluation :value :value/data]))))
            (session/close! third-session :test-end)))))))

(deftest operator-evaluation-is-not-a-conversation-turn-test
  (testing "operator forms are computational history, not model speech"
    (let [state-root (temp-root "bbagent-operator-conversation")
          session-id "operator-conversation"
          opts {:state-root state-root
                :project-root (project)
                :model-provider (provider/fake [])
                :system-prompt "test prompt"
                :session-id session-id
                :store-backend :sqlite}
          s (session/start! opts)]
      (session/operator-evaluate! s "(def only-operator 7)")
      (is (empty? @(:messages s))
          "an operator evaluation adds no message to the live session")
      (session/close! s :test-end)
      (let [resumed (session/resume! (-> opts
                                         (dissoc :project-root :session-id)
                                         (assoc :session-id session-id)))]
        (is (empty? @(:messages resumed))
            "resume must not synthesize an assistant or tool turn for it")
        (is (= 1 (count @(:replay-forms resumed)))
            "but it must appear in the computational replay program")
        (session/close! resumed :test-end)))))

(deftest operator-partial-mutation-replays-test
  (testing "a form that mutates then throws still reconstructs its mutation"
    (let [state-root (temp-root "bbagent-operator-partial")
          session-id "operator-partial"
          opts {:state-root state-root
                :project-root (project)
                :model-provider (provider/fake [])
                :system-prompt "test prompt"
                :session-id session-id
                :store-backend :sqlite}
          s (session/start! opts)
          result (session/operator-evaluate!
                  s "(do (def partial-value 5) (project/read \"missing.txt\"))")]
      (is (= :error (:status result)) "the form failed after mutating")
      (session/close! s :test-end)
      (let [resumed (session/resume! (dissoc opts :project-root))]
        (is (= 5 (get-in (session/operator-evaluate! resumed "partial-value")
                         [:evaluation :value :value/data]))
            "failed forms replay too, because SCI kept the mutation")
        (session/close! resumed :test-end)))))

(deftest interrupted-operator-evaluation-fails-recovery-test
  (testing "a durable operator request with no result is an ambiguous effect"
    (let [state-root (temp-root "bbagent-operator-interrupted")
          session-id "operator-interrupted"
          opts {:state-root state-root
                :project-root (project)
                :model-provider (provider/fake [])
                :system-prompt "test prompt"
                :session-id session-id
                :store-backend :sqlite}
          s (session/start! opts)]
      ;; Simulate a crash between the durable request and its result by
      ;; appending only the request, exactly as operator-evaluate! would.
      (store/append-event! (:store s) session-id
                           {:event/type :repl/request
                            :request/id "orphan-operator-request"
                            :repl/origin :operator
                            :repl/source "(def never-finished 1)"})
      (session/close! s :test-end)
      (is (= :session-recovery-failure
             (error-category #(session/resume! (dissoc opts :project-root))))
          "resume must fail closed rather than silently drop the effect"))))

(deftest tail-read-is-bounded-test
  (testing "the first event read is bounded at the storage layer"
    (doseq [backend [:file :sqlite]]
      (testing (str "backend " (name backend))
        (let [state-root (temp-root (str "bbagent-tail-" (name backend)))
              session-id (str "tail-" (name backend))
              s (session/start! {:state-root state-root
                                 :project-root (project)
                                 :model-provider (provider/fake [])
                                 :system-prompt "test prompt"
                                 :session-id session-id
                                 :store-backend backend})]
          (dotimes [n 40]
            (session/add-user-message! s (str "message " n)))
          (let [store (:store s)
                all (store/events store session-id)
                tail (store/recent-events store session-id 10)]
            (is (> (count all) 10))
            (is (= 10 (count tail)))
            (is (= (vec (take-last 10 all)) tail)
                "the bounded tail equals the tail of the complete history")
            (is (apply < (map :event/seq tail))
                "rows are returned in ascending display order")
            (is (= :journal-storage-failure
                   (error-category #(store/recent-events store session-id 0)))))
          (session/close! s :test-end))))))

(deftest operator-repl-uses-the-bounded-context-test
  (testing "the operator REPL has exactly the model's authority"
    (let [state-root (temp-root "bbagent-tui-repl")
          agent-session (session/start! {:state-root state-root
                                         :project-root (project)
                                         :model-provider (provider/fake [])
                                         :system-prompt "test prompt"
                                         :store-backend :sqlite})
          worker (command/start-worker! {:session-atom (atom agent-session)
                                         :store-backend :sqlite
                                         :state-root state-root})]
      (try
        (command/submit! worker {:command/type :operator/repl-eval
                                 :source "(+ 1 2)"})
        (let [ok (first (filter #(= :bbagent/repl-result (:type %))
                                (drain worker
                                       #(some (fn [m] (= :bbagent/repl-result
                                                         (:type m))) %)
                                       10000)))]
          (is (= :ok (get-in ok [:result :status]))))
        (command/submit! worker {:command/type :operator/repl-eval
                                 :source "(slurp \"/etc/passwd\")"})
        (let [denied (first (filter #(and (= :bbagent/repl-result (:type %))
                                          (str/includes? (str (:source %)) "slurp"))
                                    (drain worker
                                           #(some (fn [m]
                                                    (and (= :bbagent/repl-result
                                                            (:type m))
                                                         (str/includes?
                                                          (str (:source m))
                                                          "slurp"))) %)
                                           10000)))]
          (is (= :error (get-in denied [:result :status]))
              "the operator REPL cannot reach host authority"))
        (finally
          (command/shutdown! worker)
          (session/close! agent-session :test-end))))))
