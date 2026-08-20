(ns bbagent.tui.command
  "The application command/event seam.

  The TUI never performs semantic work on the render thread.  The pure
  reducer emits inert command values; this namespace executes them on a
  dedicated worker thread using only the existing application seams
  (bbagent.session, bbagent.agent, bbagent.storage, bbagent.store, and the
  public bb4t facade) and publishes result messages back to the UI.

  Durability ordering is therefore unchanged: the worker calls exactly the
  functions the CLI calls, in the same order.  Only the reporting is
  asynchronous.

  One discipline is borrowed from the sibling charm application bbf1: every
  command catches its own failures and converts them to a domain message,
  because charm's loop rethrows and terminates on a :error message.

  Commands execute one at a time in submission order.  That serialization,
  not a generation tag, is what prevents a stale reply from landing on a
  newer session: a session switch cannot overlap the work it replaces."
  (:require [bbagent.agent :as agent]
            [bbagent.errors :as errors]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [bbagent.tui.viewmodel :as vm])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private initial-event-read 50)

(defn- error-message
  "Normalizes any throwable into the existing structured error vocabulary.
   Unrecognized throwables are reported honestly rather than relabelled."
  [throwable]
  (let [data (ex-data throwable)
        category (:bbagent/error data)]
    {:type :bbagent/error
     :error (vm/error-view
             {:category (or category :journal-storage-failure)
              :message (or (ex-message throwable) (str throwable))
              :data (cond-> (or (:error/data data) {})
                      (nil? category)
                      (assoc :error/unclassified (.getName (class throwable))))})}))

(defprotocol Worker
  (submit! [worker command] "Queues an inert command value for execution.")
  (poll-message! [worker timeout-ms]
    "Blocks up to timeout-ms for the next result message, or returns nil.")
  (shutdown! [worker] "Stops the worker thread. Idempotent."))

;; ---------------------------------------------------------------------------
;; Projections published to the UI
;; ---------------------------------------------------------------------------

(defn header-message [agent-session store-backend activity]
  {:type :bbagent/header
   :header (vm/header {:session agent-session
                       :provider-description
                       (provider/describe-provider (:provider agent-session))
                       :store-backend store-backend
                       :activity activity})})

(defn conversation-message [agent-session]
  {:type :bbagent/conversation
   :conversation (vm/conversation @(:messages agent-session))})

(defn capabilities-message [agent-session]
  {:type :bbagent/capabilities
   :capabilities (vm/capabilities
                  (get-in agent-session [:bb4t :context/description]))})

(defn- tail-events
  "Reads only what the view has not seen.  The first read is bounded and the
   rest are strictly incremental, so the complete history is never reloaded
   to refresh the pane."
  [agent-session cursor]
  (let [store (:store agent-session)
        session-id (:session-id agent-session)]
    (if cursor
      (store/events-after store session-id cursor)
      (store/recent-events store session-id initial-event-read))))

(defn events-message [agent-session cursor]
  {:type :bbagent/events :events (tail-events agent-session cursor)})

;; ---------------------------------------------------------------------------
;; Command execution
;; ---------------------------------------------------------------------------

(defn- publish! [^LinkedBlockingQueue out message]
  (.put out message))

(defn- refresh! [out agent-session cursor-atom store-backend activity]
  (publish! out (header-message agent-session store-backend activity))
  (publish! out (capabilities-message agent-session))
  (publish! out (conversation-message agent-session))
  (let [events (tail-events agent-session @cursor-atom)]
    (when (seq events)
      (reset! cursor-atom (:event/id (last events))))
    (publish! out {:type :bbagent/events :events events})))

(defn execute!
  "Executes one command against the application seams.  Pure dispatch on
   inert data; every branch reports through out."
  [{:keys [out session-atom cursor store-backend state-root]} command]
  (let [agent-session @session-atom]
    (case (:command/type command)

      :session/submit-message
      (do
        (publish! out {:type :bbagent/activity :activity :waiting-for-model
                       :status "waiting for model"})
        (let [message (agent/turn! agent-session (:text command))]
          (refresh! out agent-session cursor store-backend :finished)
          (publish! out {:type :bbagent/turn-complete :message message})))

      :operator/repl-eval
      ;; Evaluated in the session's own bounded bb4t Context: the operator
      ;; gets exactly the authority the model has, never more, and never a
      ;; trusted host REPL.  It goes through session/operator-evaluate! so
      ;; the evaluation is durable and replayed on resume; the operator and
      ;; the model mutate one Context, so operator state must reconstruct
      ;; too or a later agent form could fail to replay.
      (do
        (publish! out {:type :bbagent/activity :activity :evaluating
                       :status "evaluating in bounded context"})
        (let [result (session/operator-evaluate! agent-session (:source command))]
          (publish! out {:type :bbagent/repl-result
                         :source (:source command)
                         :result result})
          (refresh! out agent-session cursor store-backend :idle)))

      :events/poll
      (let [events (tail-events agent-session @cursor)]
        (when (seq events)
          (reset! cursor (:event/id (last events))))
        (publish! out {:type :bbagent/events :events events}))

      :view/refresh
      (refresh! out agent-session cursor store-backend :idle)

      :sessions/list
      (let [backend (:backend command)
            root (storage/open! state-root backend)]
        (try
          (publish! out {:type :bbagent/sessions
                         :backend backend
                         :sessions (vec (store/list-sessions root))})
          (finally (store/close-store! root))))

      :session/resume
      ;; Resuming swaps the live session behind the view.  It closes the
      ;; current session first, so the outgoing session is checkpointed and
      ;; its store released before another is opened.  Backend selection is
      ;; explicit: nothing infers where an existing session lives.
      (let [target (:session-id command)
            backend (:backend command)
            outgoing @session-atom]
        (publish! out {:type :bbagent/activity :activity :resuming
                       :status (str "resuming " target)})
        (session/close! outgoing :operator-switch)
        (let [resumed (session/resume!
                       {:state-root state-root
                        :session-id target
                        :model-provider (:provider outgoing)
                        :system-prompt (:system-prompt outgoing)
                        :store-backend backend})]
          (reset! session-atom resumed)
          (reset! cursor nil)
          (publish! out {:type :bbagent/session-switched
                         :session-id (:session-id resumed)})
          (refresh! out resumed cursor backend :idle)
          (publish! out (capabilities-message resumed))))

      :session/checkpoint-and-quit
      (do
        (session/close! agent-session :operator-exit)
        (publish! out {:type :bbagent/quit}))

      (publish! out {:type :bbagent/error
                     :error (vm/error-view
                             {:category :agent-invalid-action
                              :message "Unknown TUI command"
                              :data {:command command}})}))))

(defn start-worker!
  "Starts the worker thread.  Commands execute one at a time in submission
   order, which is what keeps durable append ordering identical to the CLI."
  [{:keys [session-atom store-backend state-root] :as context}]
  (let [in (LinkedBlockingQueue.)
        out (LinkedBlockingQueue.)
        running (atom true)
        cursor (atom nil)
        context (assoc context :out out :cursor cursor)
        thread (Thread.
                (fn []
                  (try
                    (while @running
                      (when-let [command (.poll in 100 TimeUnit/MILLISECONDS)]
                        (try
                          (execute! context command)
                          (catch Throwable failure
                            ;; Never allow a failure to reach charm as :error;
                            ;; charm's loop rethrows that and kills the TUI.
                            (.put out (error-message failure))))))
                    (catch InterruptedException _
                      ;; shutdown! interrupts the poll; this is a normal exit.
                      nil))))]
    (.setName thread "bbagent-tui-worker")
    (.setDaemon thread true)
    (.start thread)
    (reify Worker
      (submit! [_ command] (.put in command))
      (poll-message! [_ timeout-ms] (.poll out timeout-ms TimeUnit/MILLISECONDS))
      (shutdown! [_] (reset! running false) (.interrupt thread)))))
