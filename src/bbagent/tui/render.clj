(ns bbagent.tui.render
  "Pure rendering of view state to terminal text.

  Every function here is a pure function of the view state produced by
  bbagent.tui.state and the projections produced by bbagent.tui.viewmodel.
  Keeping rendering pure is what lets the layout be tested without a
  terminal."
  (:require [bbagent.tui.viewmodel :as vm]
            [charm.style.core :as style]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def ^:private label (style/style :faint true))
(def ^:private strong (style/style :bold true))
(def ^:private focused (style/style :fg style/cyan :bold true))
(def ^:private warn (style/style :fg style/yellow))
(def ^:private bad (style/style :fg style/red :bold true))
(def ^:private human (style/style :fg style/green :bold true))
(def ^:private agent-style (style/style :fg style/cyan))
(def ^:private tool-style (style/style :faint true))

(defn- fit
  "Truncates to width without wrapping, so a pane never bleeds into another."
  [text width]
  (let [text (str/replace (str text) #"[\r\n\t]" " ")]
    (cond
      (<= width 0) ""
      (<= (count text) width) text
      (<= width 1) (subs text 0 width)
      :else (str (subs text 0 (dec width)) "…"))))

(defn- pad [text width]
  (let [text (fit text width)]
    (str text (apply str (repeat (max 0 (- width (count text))) \space)))))

(defn- segments->line
  "Renders [style text] segments into one line whose *plain* width never
   exceeds width.  Styling adds invisible escape bytes, so the budget is
   measured on the text, never on the rendered string."
  [segments width]
  (first
   (reduce (fn [[out remaining] [st text]]
             (if (<= remaining 0)
               [out 0]
               (let [text (fit text remaining)]
                 [(str out (if st (style/render st text) text))
                  (- remaining (count text))])))
           ["" width]
           segments)))

(defn- pane-title [title focused?]
  (style/render (if focused? focused label)
                (str (if focused? "▸ " "  ") title)))

(defn- activity-style [activity]
  (case activity
    :failed bad
    (:waiting-for-model :evaluating :resuming) warn
    strong))

(defn header-lines
  "One-line orientation banner plus a coordinate line.  Secrets never
   appear: only provider, endpoint, and model come from the description."
  [{:keys [header store/backend]} width]
  (let [{session-short :session/id-short
         run-short :run/id-short
         root :project/root
         profile :context/profile
         coordinate-short :context/coordinate-short
         :keys [model provider activity]} header]
    [(segments->line
      [[strong "bbagent"]
       [label "  session "] [nil (or session-short "-")]
       [label "  run "] [nil (or run-short "-")]
       [label "  store "] [nil (name (or backend :?))]
       [label "  "]
       [(activity-style activity) (name (or activity :idle))]]
      width)
     (segments->line
      [[label "project "] [nil (str (or root "-"))]
       [label "  model "] [nil (str (or model "-"))]
       [label "  provider "] [nil (str (or provider "-"))]
       [label "  profile "] [nil (str (or profile "-"))]
       [label "  ctx "] [nil (str (or coordinate-short "-"))]]
      width)]))

(defn conversation-lines [{:keys [conversation scroll]} width height]
  (let [rows (into []
                   (mapcat
                    (fn [{:turn/keys [kind text action-id-short truncated?
                                      full-characters]}]
                      (let [[tag st] (case kind
                                       :human ["you" human]
                                       :agent-final ["agent" agent-style]
                                       :agent-action [(str "repl " action-id-short)
                                                      tool-style]
                                       :tool-result [(str "result " action-id-short)
                                                     tool-style]
                                       ["-" tool-style])]
                        [(str (style/render st (pad tag 14)) " "
                              (fit text (max 0 (- width 15))))
                         (when truncated?
                           (style/render label
                                         (str (pad "" 14) " "
                                              (fit (str "  (" full-characters
                                                        " characters; inspect the event"
                                                        " for the complete value)")
                                                   (max 0 (- width 15))))))])))
                   conversation)
        rows (remove nil? rows)
        offset (min (get scroll :conversation 0)
                    (max 0 (- (count rows) height)))
        visible (->> rows (drop-last offset) (take-last height))]
    (vec visible)))

(defn capability-lines
  "Renders authority from real bb4t metadata.  When the context description
   is absent the pane says so rather than inventing a capability list."
  [{:keys [capabilities]} width height]
  (if-not capabilities
    [(style/render label "no context description")]
    (let [{:keys [profile effective-grants vars limits projected-class-count
                  supplied-import-count]} capabilities]
      (->> (concat
            [(str (style/render label "profile ") (str profile))]
            [(style/render label "capabilities")]
            (map #(str "  " (str %)) effective-grants)
            [(style/render label "Vars")]
            (map #(str "  " %) vars)
            [(style/render label "limits")]
            (map (fn [[k v]] (str "  " k " " v)) limits)
            [(style/render label
                           (str "classes " projected-class-count
                                "  imports " supplied-import-count))])
           (map #(fit % width))
           (take height)
           vec))))

(defn event-lines [{:keys [events selected scroll]} width height]
  (let [rows (:rows events)
        current (get selected :event)
        lines (mapv
               (fn [{:event/keys [seq type status] :as row}]
                 (let [marker (if (= seq current) "▸" " ")
                       text (str marker " "
                                 (pad (str seq) 5)
                                 (pad (str type) 26)
                                 (pad (or (some-> status str) "") 14)
                                 (or (:request/id-short row)
                                     (:action/id-short row) ""))]
                   (if (= seq current)
                     (style/render focused (fit text width))
                     (fit text width))))
               rows)
        offset (min (get scroll :events 0) (max 0 (- (count lines) height)))]
    (vec (->> lines (drop-last offset) (take-last height)))))

(defn input-lines [{:keys [input input/mode focus]} width]
  (let [prompt (case mode :repl "repl> " "you> ")
        st (if (= :input focus) focused label)]
    [(segments->line [[st prompt] [nil (str (:buffer input))]] width)
     (segments->line
      [[label (if (= :repl mode)
                "operator REPL: the session's bounded context; journaled and replayed on resume"
                "message goes to the agent; Ctrl-T switches to the operator REPL")]]
      width)]))

(defn status-line [{:keys [status error]} width]
  (if error
    (segments->line
     [[bad (str (:error/label error) ": " (:error/message error))]
      [label "   (Enter on the event pane for detail)"]]
     width)
    (segments->line
     [[label "[Tab] pane  [Ctrl-T] mode  [Ctrl-R] events  "]
      [label "[Ctrl-S] sessions  [F1] help  [Ctrl-Q] quit   "]
      [strong (str (or status ""))]]
     width)))

(def help-text
  ["bbagent A1 keys" ""
   "  Tab            next pane"
   "  Enter          submit input, or inspect the selected event"
   "  Esc            close a panel, or clear the input line"
   "  Ctrl-T         switch between agent message and operator REPL"
   "  Ctrl-R         refresh the event tail"
   "  Ctrl-S         list sessions for the current backend"
   "  Up/Down        input history, or event selection"
   "  PageUp/Down    scroll the focused pane"
   "  Ctrl-C         clear the pending input line only"
   "  Ctrl-Q         checkpoint and exit"
   "  F1             this help"
   ""
   "Ctrl-C cancels the input line. It does not stop a provider call or"
   "interrupt a running evaluation; bbagent has no worker termination yet."
   ""
   "The operator REPL uses the session's bounded bb4t context, so it has"
   "exactly the model's authority. Operator and model share one context, so"
   "operator evaluations are journaled and replayed on resume too. They do"
   "not become conversation turns; the model is never told it said them."
   "" "  Enter or Esc to close"])

(defn- modal-lines [state width height]
  (case (:modal state)
    :help (mapv #(segments->line [[nil %]] width) (take height help-text))
    :event-detail
    (let [row (some #(when (= (get-in state [:selected :event]) (:event/seq %)) %)
                    (get-in state [:events :rows]))]
      (into [(segments->line [[strong "event detail (structured value)"]] width)]
            (mapv #(fit % width)
                  (take (dec height)
                        (str/split-lines
                         (with-out-str
                           (pprint/pprint (:event/value row))))))))
    :sessions
    (let [current (get-in state [:selected :session])
          live (:session/id state)]
      (into [(segments->line
              [[strong "sessions"]
               [label (str "  backend " (name (or (:store/backend state) :?))
                           "  Up/Down select, Enter resume, Esc close")]]
              width)]
            (mapv (fn [id]
                    (let [marker (cond (= id current) "> "
                                       :else "  ")
                          suffix (if (= id live) "  (current)" "")]
                      (if (= id current)
                        (segments->line [[focused (str marker id suffix)]] width)
                        (segments->line [[nil (str marker id)]
                                         [label suffix]] width))))
                  (take (dec height) (:sessions state)))))
    nil))

(defn render
  "Renders the complete screen.  Pure: same state in, same string out."
  [{:keys [size] :as state}]
  (let [{:keys [cols rows]} size
        cols (max 40 (or cols 80))
        rows (max 12 (or rows 24))
        body-height (- rows 2 2 3)
        left-width (max 24 (quot cols 3))
        right-width (- cols left-width 1)
        top-height (max 3 (quot body-height 2))
        bottom-height (max 3 (- body-height top-height))]
    (if-let [modal (modal-lines state cols (- rows 3))]
      (str/join "\r\n"
                (concat (header-lines state cols)
                        [""] modal
                        [""] [(status-line state cols)]))
      (let [context-pane (capability-lines state left-width top-height)
            events-pane (event-lines state left-width bottom-height)
            convo-pane (conversation-lines state right-width top-height)
            repl-pane (map #(fit (str "repl> " (:source %) " => "
                                      (vm/repl-result-summary (:result %)))
                                 right-width)
                           (take-last bottom-height (:repl/log state)))
            left (concat [(pane-title "Context" (= :context (:focus state)))]
                         context-pane
                         (repeat (max 0 (- top-height (count context-pane))) "")
                         [(pane-title "Recent Events" (= :events (:focus state)))]
                         events-pane
                         (repeat (max 0 (- bottom-height (count events-pane))) ""))
            right (concat [(pane-title "Conversation"
                                       (= :conversation (:focus state)))]
                          convo-pane
                          (repeat (max 0 (- top-height (count convo-pane))) "")
                          [(pane-title "Operator REPL" false)]
                          repl-pane
                          (repeat (max 0 (- bottom-height (count repl-pane))) ""))
            ;; Both columns are padded to the same bounded height before
            ;; zipping.  Zipping two infinite seqs would never terminate.
            column-height (+ body-height 2)
            fill (fn [lines] (take column-height (concat lines (repeat ""))))
            body (mapv (fn [l r] (str (pad l left-width) " " (fit r right-width)))
                       (fill left)
                       (fill right))]
        (str/join "\r\n"
                  (concat (header-lines state cols)
                          (take (+ body-height 2) body)
                          (input-lines state cols)
                          [(status-line state cols)]))))))
