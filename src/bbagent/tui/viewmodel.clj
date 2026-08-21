(ns bbagent.tui.viewmodel
  "Pure projections from durable application state onto view data.

  Every function here is a pure function of values already owned by
  AgentSession, the store contract, or a bb4t context description.  This
  namespace holds no state, performs no IO, and never decides authority; it
  only decides what the operator sees.  Keeping it pure is what makes the
  TUI a projection rather than a second model."
  (:require [clojure.string :as str]))

(def ^:private abbreviated-id-length 8)

(defn abbreviate
  "Shortens an identifier for display.  The full value stays available in
   the view model so an operator can inspect it."
  [value]
  (let [text (some-> value str)]
    (cond
      (str/blank? text) nil
      (<= (count text) abbreviated-id-length) text
      :else (subs text 0 abbreviated-id-length))))

(defn- digest-tail
  "bb4t coordinates are sha256:<hex>.  Operators orient on the tail, so show
   the algorithm and the leading hex rather than a truncated prefix alone."
  [coordinate]
  (when-let [text (some-> coordinate str not-empty)]
    (if-let [[_ algorithm hex] (re-matches #"([a-z0-9]+):([0-9a-f]+)" text)]
      (str algorithm ":" (subs hex 0 (min 12 (count hex))))
      text)))

(def activity-states
  "The activity values the header may display."
  #{:idle :waiting-for-model :evaluating :failed :finished :resuming})

(defn header
  "Projects orientation data.  Secrets are never included: the provider
   description carries endpoint and model, and bbagent.provider already
   excludes the API key from it."
  [{:keys [session provider-description store-backend activity]}]
  (let [context (get-in session [:bb4t :context/description])
        coordinate (:coordinate session)]
    {:project/root (get-in session [:project :project/root])
     :project/revision (get-in session [:project :project/revision])
     :project/dirty? (get-in session [:project :project/dirty?])
     :session/id (:session-id session)
     :session/id-short (abbreviate (:session-id session))
     :run/id (:run-id session)
     :run/id-short (abbreviate (:run-id session))
     :model (:model provider-description)
     :provider (:provider provider-description)
     :endpoint (:endpoint provider-description)
     :store/backend store-backend
     :context/coordinate (:context/coordinate context)
     :context/coordinate-short (digest-tail (:context/coordinate context))
     :context/profile (get-in context [:context/effective :context/profile])
     :runtime/coordinate-short
     (digest-tail (get-in context [:context/effective :runtime/coordinate]))
     :activity (or activity :idle)
     :coordinate/present? (some? coordinate)}))

(def ^:private max-result-preview 240)

(defn- summarize-structured
  "Renders a structured value for the conversation pane without dumping it.
   The complete value stays reachable through event inspection."
  [value]
  (let [text (pr-str value)]
    (if (<= (count text) max-result-preview)
      {:text text :truncated? false}
      {:text (str (subs text 0 max-result-preview) " ...")
       :truncated? true
       :full/characters (count text)})))

(defn conversation
  "Projects the session's provider-neutral messages onto display turns.

   The conversation is derived, never canonical: it is a projection of the
   messages AgentSession already reconstructs from the durable journal.
   The system prompt is not shown as a turn."
  [messages]
  (into []
        (comp
         (remove #(= :system (:role %)))
         (map-indexed
          (fn [index {:keys [role content actions] :as message}]
            (case role
              :user {:turn/index index :turn/kind :human :turn/text (str content)}

              :tool
              (let [{:keys [text truncated? full/characters]}
                    (summarize-structured content)]
                (cond-> {:turn/index index
                         :turn/kind :tool-result
                         :turn/action-id (:action/id message)
                         :turn/action-id-short (abbreviate (:action/id message))
                         :turn/text text}
                  truncated? (assoc :turn/truncated? true
                                    :turn/full-characters characters)))

              :assistant
              (if (and (nil? content) (seq actions))
                {:turn/index index
                 :turn/kind :agent-action
                 :turn/action-id (:action/id (first actions))
                 :turn/action-id-short (abbreviate (:action/id (first actions)))
                 :turn/text (str/trim
                             (str (get-in (first actions)
                                          [:action/value :source])))}
                {:turn/index index :turn/kind :agent-final
                 :turn/text (str content)})

              {:turn/index index :turn/kind :other
               :turn/text (pr-str message)}))))
        messages))

(defn capabilities
  "Projects the bounded Context's authority from real bb4t metadata.

   Nothing here is hard-coded: the profile, grants, projected Vars, limits,
   and resources all come from the ContextSpec and the context description
   that bb4t returns, so a capability the runtime does not grant cannot
   appear and a capability it does grant cannot be omitted."
  [context-description]
  (let [{:keys [context/spec context/effective context/surface]} context-description
        projections (:projections surface)]
    {:profile (:context/profile effective)
     :requested-capabilities (vec (sort (:requested-capabilities spec)))
     :authorized-capabilities (vec (sort (:authorized-capabilities spec)))
     :effective-grants (vec (sort (:context/grants effective)))
     :operations (into []
                       (map (fn [{:keys [capability/id operation/id
                                         sci/var effects doc arglists]
                                  :as projection}]
                              {:capability (:capability/id projection)
                               :operation (:operation/id projection)
                               :var (str var)
                               :effects (vec (sort effects))
                               :doc doc
                               :arglists (vec arglists)}))
                       projections)
     :vars (vec (sort (map (comp str :sci/var) projections)))
     :namespaces (vec (sort (set (map (comp namespace :sci/var) projections))))
     :limits (:context/limits effective)
     :resources (:context/resources effective)
     :projected-class-count (:projected-class-count surface)
     :supplied-import-count (:supplied-import-count surface)
     :projected-var-count (:total-projected-var-count surface)}))

(defn- event-status
  "Extracts a display status without parsing log strings."
  [event]
  (case (:event/type event)
    :model/response (:response/status event)
    :repl/result (get-in event [:repl/result :status])
    :session/checkpoint (:checkpoint/reason event)
    :session/ended (:session/end-reason event)
    nil))

(defn- event-usage [event]
  (when (= :model/response (:event/type event))
    (get-in event [:model/response :usage])))

(defn event-row
  "Projects one durable event onto a compact row.  The complete event value
   is retained so detail inspection works on data, not on rendered text."
  [event]
  (let [request-id (:request/id event)
        action-id (:action/id event)]
    (cond-> {:event/seq (:event/seq event)
             :event/id (:event/id event)
             :event/type (:event/type event)
             :event/time (:event/time event)
             :event/value event}
      request-id (assoc :request/id request-id
                        :request/id-short (abbreviate request-id))
      action-id (assoc :action/id action-id
                       :action/id-short (abbreviate action-id))
      (event-status event) (assoc :event/status (event-status event))
      (event-usage event) (assoc :event/usage (event-usage event)))))

(defn event-rows [events]
  (into [] (map event-row) events))

(defn append-events
  "Appends newly read event rows to a bounded window and advances the tail
   cursor.  Older rows fall out of the window rather than accumulating: the
   store, not the UI, remains the history of record.

   Returns {:rows ... :cursor ...} where cursor is the last event ID seen,
   which is exactly what store/events-after consumes next."
  [{:keys [rows cursor]} new-events limit]
  (let [added (event-rows new-events)
        combined (into (vec rows) added)
        overflow (max 0 (- (count combined) limit))]
    {:rows (vec (drop overflow combined))
     :cursor (or (:event/id (last added)) cursor)
     :added (count added)
     :dropped overflow}))

(def error-labels
  "Operator-facing labels for the existing structured error categories.
   The category set is bbagent.errors/categories; nothing new is invented."
  {:provider-failure "provider failure"
   :provider-malformed-response "malformed provider response"
   :agent-invalid-action "invalid agent action"
   :bb4t-authorization-denial "authorization denied"
   :bb4t-evaluation-failure "evaluation failed"
   :journal-storage-failure "storage failure"
   :session-recovery-failure "session recovery failure"
   :user-cancellation "cancelled"})

(defn error-view
  "Projects a structured bbagent error onto a concise operator message plus
   retained structured detail.  Stack traces are never part of the concise
   message; the detail is data an inspector can navigate."
  [{:keys [category message data]}]
  {:error/category category
   :error/label (get error-labels category "error")
   :error/message (or message "(no message)")
   :error/detail data
   :error/inspectable? (some? data)})

(def ^:private max-repl-summary-characters 240)

(defn repl-result-summary
  "One line describing what an operator evaluation returned.

  The REPL pane previously rendered only the status, so every successful
  evaluation read `=> :ok` whatever it produced. That was survivable while the
  bounded surface returned trivia; once a capability returns a listing or a set
  of search matches, the value is the entire point of having run it.

  Pure and total: an error reports its category, a described value reports its
  data, a value too large to describe reports its preview and size, and
  anything the runtime declined to describe reports its type rather than
  pretending to a value."
  [result]
  (let [value (get-in result [:evaluation :value])
        out (some-> (get-in result [:evaluation :out]) str/trim not-empty)
        body
        (cond
          (= :error (:status result))
          (str ":error "
               (name (or (get-in result [:error :bbagent/error]) :unknown))
               (when-let [m (get-in result [:error :error/message])]
                 (str " " m)))

          (contains? value :value/data)
          (pr-str (:value/data value))

          (:value/preview value)
          (str (:value/preview value)
               " ... (" (or (:value/characters value)
                            (:value/encoded-characters value))
               " chars)")

          (= :opaque (:value/kind value))
          (str "#opaque " (or (:value/type value) "unknown"))

          :else (pr-str (:status result)))
        body (if (> (count body) max-repl-summary-characters)
               (str (subs body 0 max-repl-summary-characters) "...")
               body)]
    (if out (str out " | " body) body)))
