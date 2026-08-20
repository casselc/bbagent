(ns bbagent.tui.app
  "charm program wiring: the only namespace that touches the terminal.

  The program is a controller.  Keys become inert commands through the pure
  reducer, the worker executes them against the application seams, and its
  result messages become new view state.  No domain truth is stored here.

  charm creates its message channel internally and does not expose it, so an
  outside thread cannot post a message.  The seam is therefore a pump: one
  long-lived command blocks briefly on the worker's queue and always returns
  a message, and the handler for that message re-issues it.  This is the
  same self-sustaining command pattern the sibling charm application bbf1
  uses to drive playback frames.  Exactly one command is ever outstanding,
  so exactly one core.async dispatch thread is ever occupied."
  (:require [bbagent.tui.command :as command]
            [bbagent.tui.render :as render]
            [bbagent.tui.state :as state]
            [charm.message :as msg]
            [charm.program :as program]))

(def ^:private pump-timeout-ms 80)

(def ^:private tick {:type :bbagent/tick})

(defn- pump-cmd
  "One outstanding command that waits briefly for worker output.  It always
   returns a message, because charm drops a nil result and the pump would
   otherwise stop."
  [worker]
  (program/cmd
   (fn []
     (try
       (or (command/poll-message! worker pump-timeout-ms) tick)
       (catch Throwable failure
         ;; Never return charm's :error message: charm's loop rethrows it
         ;; and terminates the program.
         {:type :bbagent/error
          :error {:error/category :journal-storage-failure
                  :error/label "tui pump failure"
                  :error/message (str (ex-message failure))
                  :error/detail {:error/type (.getName (class failure))}}})))))

(defn- dispatch
  "Applies a command list by submitting to the worker, then re-arms the pump."
  [worker commands]
  (doseq [c commands] (command/submit! worker c))
  (pump-cmd worker))

(defn make-update
  "Builds charm's update function.  Pure decisions live in bbagent.tui.state;
   this only routes messages and re-arms the pump."
  [worker]
  (fn [view msg]
    (cond
      (msg/window-size? msg)
      [(state/resize view (:width msg) (:height msg)) (pump-cmd worker)]

      (msg/key-press? msg)
      ;; A quit keystroke submits the checkpoint command like any other and
      ;; keeps the pump armed; the loop exits when the worker reports
      ;; :bbagent/quit, so the checkpoint is durable before teardown.
      (let [[view' commands] (state/handle-key view msg)]
        [view' (dispatch worker commands)])

      (= :bbagent/tick (:type msg))
      [view (pump-cmd worker)]

      (= :bbagent/quit (:type msg))
      [(assoc view :quit? true) program/quit-cmd]

      (some? (:type msg))
      [(state/handle-result view msg) (pump-cmd worker)]

      :else [view (pump-cmd worker)])))

(defn start!
  "Runs the TUI over an already-open AgentSession.

  The session, its store, and its bounded Context are created by the caller
  through the ordinary application seams and are never constructed here."
  [{:keys [agent-session store-backend state-root event-window]}]
  (let [session-atom (atom agent-session)
        worker (command/start-worker!
                {:session-atom session-atom
                 :store-backend store-backend
                 :state-root state-root})]
    (try
      (program/run
       {:alt-screen true
        :hide-cursor false
        :fps 30
        :init (fn []
                [(state/initial
                  (cond-> {:store-backend store-backend
                           :session-id (:session-id agent-session)}
                    event-window (assoc :event-window event-window)))
                 (dispatch worker [{:command/type :view/refresh}
                                   {:command/type :events/poll}])])
        :update (make-update worker)
        :view render/render})
      (finally
        (command/shutdown! worker)))))
