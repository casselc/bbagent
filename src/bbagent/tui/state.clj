(ns bbagent.tui.state
  "Transient TUI view state and its pure reducer.

  This namespace holds focus, scroll, input buffer, selection, modal
  visibility, terminal size, and the bounded event window.  It deliberately
  holds no durable truth: no AgentSession, no store handle, no bb4t Context,
  and no authority.  Domain state lives behind the application seams and is
  projected in through bbagent.tui.viewmodel.

  The reducer is pure.  It returns [state commands], where commands are
  inert data describing semantic work for the application worker to perform.
  Nothing here performs that work, so key handling can be tested without a
  terminal, a provider, or a store."
  (:require [bbagent.tui.viewmodel :as vm]
            [clojure.string :as str]))

(def panes
  "Focusable panes, in Tab order."
  [:input :conversation :events :context])

(def ^:private pane-set (set panes))

(def default-event-window
  "How many projected event rows the view retains.  The store remains the
   history of record; older rows are re-read on demand rather than kept."
  200)

(defn initial
  "Builds the initial view state.  Everything durable arrives later as
   projections; nothing durable is constructed here."
  [{:keys [cols rows store-backend session-id event-window]
    :or {cols 80 rows 24 event-window default-event-window}}]
  {:focus :input
   :size {:cols cols :rows rows}
   :input {:buffer "" :cursor 0 :history [] :history-index nil}
   :input/mode :chat
   :scroll {:conversation 0 :events 0 :context 0}
   :selected {:event nil :session nil}
   :modal nil
   :activity :idle
   :status "ready"
   :error nil
   :header {}
   :conversation []
   :repl/log []
   :capabilities nil
   :events {:rows [] :cursor nil}
   :event-window event-window
   :sessions nil
   :store/backend store-backend
   :session/id session-id
   :quit? false})

;; ---------------------------------------------------------------------------
;; Pure helpers
;; ---------------------------------------------------------------------------

(defn- cycle-focus [focus delta]
  (let [index (or (first (keep-indexed #(when (= %2 focus) %1) panes)) 0)]
    (nth panes (mod (+ index delta) (count panes)))))

(defn- scrollable [focus]
  (when (contains? #{:conversation :events :context} focus) focus))

(defn- scroll-by [state delta]
  (if-let [pane (scrollable (:focus state))]
    (update-in state [:scroll pane] #(max 0 (+ (or % 0) delta)))
    state))

(defn- page-size [state]
  (max 1 (- (get-in state [:size :rows] 24) 8)))

(defn- insert-text [{:keys [buffer cursor] :as input} text]
  (let [cursor (min (max 0 cursor) (count buffer))]
    (assoc input
           :buffer (str (subs buffer 0 cursor) text (subs buffer cursor))
           :cursor (+ cursor (count text)))))

(defn- backspace [{:keys [buffer cursor] :as input}]
  (if (pos? cursor)
    (assoc input
           :buffer (str (subs buffer 0 (dec cursor)) (subs buffer cursor))
           :cursor (dec cursor))
    input))

(defn- delete-forward [{:keys [buffer cursor] :as input}]
  (if (< cursor (count buffer))
    (assoc input :buffer (str (subs buffer 0 cursor) (subs buffer (inc cursor))))
    input))

(defn- move-cursor [{:keys [buffer cursor] :as input} delta]
  (assoc input :cursor (min (count buffer) (max 0 (+ cursor delta)))))

(defn- clear-input [input]
  (assoc input :buffer "" :cursor 0 :history-index nil))

(defn- recall-history
  "Moves through submitted input history.  delta -1 is older, +1 is newer."
  [{:keys [history history-index] :as input} delta]
  (let [n (count history)]
    (if (zero? n)
      input
      (let [index (cond
                    (nil? history-index) (if (neg? delta) (dec n) nil)
                    :else (+ history-index delta))]
        (cond
          (nil? index) input
          (neg? index) (assoc input :history-index 0
                              :buffer (nth history 0)
                              :cursor (count (nth history 0)))
          (>= index n) (assoc input :history-index nil :buffer "" :cursor 0)
          :else (assoc input :history-index index
                       :buffer (nth history index)
                       :cursor (count (nth history index))))))))

(defn- remember-submission [input text]
  (-> input
      (update :history (fn [h] (if (= text (peek h)) h (conj (vec h) text))))
      clear-input))

(defn- selectable-seqs [state]
  (mapv :event/seq (get-in state [:events :rows])))

(defn- move-selection [state delta]
  (let [seqs (selectable-seqs state)]
    (if (empty? seqs)
      state
      (let [current (get-in state [:selected :event])
            index (or (first (keep-indexed #(when (= %2 current) %1) seqs))
                      (dec (count seqs)))
            next-index (min (dec (count seqs)) (max 0 (+ index delta)))]
        (assoc-in state [:selected :event] (nth seqs next-index))))))

(defn selected-event
  "Returns the complete structured event value for the current selection, so
   inspection operates on data rather than on rendered text."
  [state]
  (let [target (get-in state [:selected :event])]
    (some #(when (= target (:event/seq %)) %) (get-in state [:events :rows]))))

;; ---------------------------------------------------------------------------
;; Submission
;; ---------------------------------------------------------------------------

(defn- submit [state]
  (let [text (str/trim (get-in state [:input :buffer]))]
    (if (str/blank? text)
      [state nil]
      (let [mode (:input/mode state)
            command (case mode
                      :chat {:command/type :session/submit-message :text text}
                      :repl {:command/type :operator/repl-eval :source text})]
        [(-> state
             (update :input remember-submission text)
             (assoc :activity (if (= :chat mode) :waiting-for-model :evaluating)
                    :status (if (= :chat mode) "submitting message" "evaluating")
                    :error nil))
         [command]]))))

;; ---------------------------------------------------------------------------
;; Key handling
;; ---------------------------------------------------------------------------

(defn- key-of [msg] (:key msg))

(defn- printable-runes
  "charm reports typed characters as :runes with a string key.  A space
   arrives that way too, so this must not treat blank strings as absent;
   rejecting them silently swallowed every space in the input line."
  [msg]
  (let [k (key-of msg)]
    (when (and (string? k) (pos? (count k))
               (not (:ctrl msg)) (not (:alt msg)))
      k)))

(defn- global-key [state msg]
  (let [k (key-of msg)
        ctrl (:ctrl msg)]
    (cond
      (and ctrl (= "q" k))
      [(assoc state :quit? true :status "checkpointing and exiting")
       [{:command/type :session/checkpoint-and-quit}]]

      (= :escape k)
      [(cond-> state
         (:modal state) (assoc :modal nil)
         (nil? (:modal state)) (update :input clear-input))
       nil]

      (and ctrl (= "c" k))
      ;; Ctrl-C cancels the pending input line.  It does not terminate the
      ;; process, kill a provider call, or interrupt a running evaluation;
      ;; see docs for exactly what interruption bbagent can and cannot do.
      [(-> state (update :input clear-input)
           (assoc :status "input cancelled")) nil]

      (= :f1 k)
      [(assoc state :modal (when-not (= :help (:modal state)) :help)) nil]

      :else nil)))

(defn handle-key
  "Pure key reducer.  Returns [state commands]."
  [state msg]
  (or
   (global-key state msg)
   (let [k (key-of msg)
         ctrl (:ctrl msg)
         focus (:focus state)
         modal (:modal state)]
     (cond
       ;; A modal consumes navigation until dismissed.
       (= :sessions modal)
       (let [sessions (vec (:sessions state))
             current (get-in state [:selected :session])
             index (or (first (keep-indexed #(when (= %2 current) %1) sessions)) 0)]
         (cond
           (= :up k) [(assoc-in state [:selected :session]
                                (nth sessions (max 0 (dec index))
                                     current)) nil]
           (= :down k) [(assoc-in state [:selected :session]
                                  (nth sessions
                                       (min (dec (count sessions)) (inc index))
                                       current)) nil]
           (= :enter k)
           (if-let [target (get-in state [:selected :session])]
             (if (= target (:session/id state))
               [(assoc state :modal nil :status "already in that session") nil]
               [(assoc state :modal nil :activity :resuming
                       :status (str "resuming " target))
                [{:command/type :session/resume
                  :session-id target
                  :backend (:store/backend state)}]])
             [(assoc state :modal nil) nil])
           :else [state nil]))

       modal
       (cond
         (= :enter k) [(assoc state :modal nil) nil]
         :else [state nil])

       (= :tab k)
       [(update state :focus cycle-focus (if (:shift msg) -1 1)) nil]

       (and ctrl (= "t" k))
       (let [mode (if (= :chat (:input/mode state)) :repl :chat)]
         [(assoc state :input/mode mode
                 :status (str "input mode: " (name mode))) nil])

       (and ctrl (= "r" k))
       [(assoc state :status "refreshing events")
        [{:command/type :events/poll}]]

       (and ctrl (= "s" k))
       [(assoc state :status "listing sessions")
        [{:command/type :sessions/list
          :backend (:store/backend state)}]]

       (= :page-up k) [(scroll-by state (page-size state)) nil]
       (= :page-down k) [(scroll-by state (- (page-size state))) nil]

       (= :input focus)
       (cond
         (= :enter k) (submit state)
         (= :backspace k) [(update state :input backspace) nil]
         (= :delete k) [(update state :input delete-forward) nil]
         (= :left k) [(update state :input move-cursor -1) nil]
         (= :right k) [(update state :input move-cursor 1) nil]
         (= :home k) [(assoc-in state [:input :cursor] 0) nil]
         (= :end k) [(assoc-in state [:input :cursor]
                               (count (get-in state [:input :buffer]))) nil]
         (= :up k) [(update state :input recall-history -1) nil]
         (= :down k) [(update state :input recall-history 1) nil]
         (= :space k) [(update state :input insert-text " ") nil]
         :else (if-let [text (printable-runes msg)]
                 [(update state :input insert-text text) nil]
                 [state nil]))

       (= :events focus)
       (cond
         (= :up k) [(move-selection state -1) nil]
         (= :down k) [(move-selection state 1) nil]
         (= :enter k) [(if (selected-event state)
                         (assoc state :modal :event-detail)
                         state) nil]
         :else [state nil])

       :else [state nil]))))

;; ---------------------------------------------------------------------------
;; Application result handling
;; ---------------------------------------------------------------------------

(defn handle-result
  "Pure reducer for messages produced by the application worker.  These
   messages carry projections of durable state; this function never derives
   domain truth itself."
  [state msg]
  (case (:type msg)
    :bbagent/activity
    (assoc state :activity (:activity msg)
           :status (or (:status msg) (:status state)))

    :bbagent/header
    (assoc state :header (:header msg))

    :bbagent/conversation
    (assoc state :conversation (:conversation msg))

    :bbagent/capabilities
    (assoc state :capabilities (:capabilities msg))

    :bbagent/events
    (let [{:keys [rows cursor added dropped]}
          (vm/append-events (:events state) (:events msg) (:event-window state))]
      (-> state
          (assoc :events {:rows rows :cursor cursor})
          (cond-> (pos? (or added 0))
            (assoc :status (str "+" added " event" (when (not= 1 added) "s")
                                (when (pos? (or dropped 0))
                                  (str ", " dropped " scrolled out")))))))

    :bbagent/sessions
    (assoc state :sessions (:sessions msg) :modal :sessions
           :selected (assoc (:selected state) :session
                            (first (:sessions msg))))

    :bbagent/session-switched
    (assoc state :session/id (:session-id msg)
           :modal nil
           :events {:rows [] :cursor nil}
           :conversation []
           :repl/log []
           :selected {:event nil :session nil}
           :activity :idle
           :status (str "resumed " (:session-id msg)))

    :bbagent/error
    (assoc state :error (:error msg) :activity :failed
           :status (str "error: " (:error/label (:error msg))))

    :bbagent/repl-result
    ;; Operator REPL output is transient view state.  It is deliberately not
    ;; durable: the evaluation ran in the session's bounded Context but was
    ;; not journaled, so it is not replayed on resume.
    (-> state
        (update :repl/log (fn [log]
                            (vec (take-last 100
                                            (conj (vec log)
                                                  {:source (:source msg)
                                                   :result (:result msg)})))))
        (assoc :activity :idle :status "operator evaluation complete"))

    :bbagent/turn-complete
    (assoc state :activity :finished :status "turn complete" :error nil)

    :bbagent/quit
    (assoc state :quit? true)

    state))

(defn resize [state cols rows]
  (assoc state :size {:cols cols :rows rows}))
