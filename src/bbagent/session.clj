(ns bbagent.session
  (:require [bbagent.bb4t :as bb4t]
            [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
            [bbagent.provider :as provider]
            [bbagent.storage :as storage]
            [bbagent.store :as store])
  (:import [java.util UUID]))

(defrecord AgentSession
  [session-id run-id project provider system-prompt store bb4t coordinate
   messages replay-forms unsubscribe closed])

(defn- append-session-event! [^AgentSession session event]
  (store/append-event! (:store session) (:session-id session) event))

(defn- provider-coordinate [model-provider]
  (let [{:keys [provider endpoint model reasoning-effort allow-insecure-http]
         :as description}
        (provider/describe-provider model-provider)]
    {:description description
     :provider provider
     :endpoint endpoint
     :model model
     :reasoning-effort reasoning-effort
     :allow-insecure-http allow-insecure-http}))

(defn- envelope [session-id run-id project model-provider system-prompt runtime]
  (let [{:keys [provider endpoint model reasoning-effort allow-insecure-http]}
        (provider-coordinate model-provider)]
    (coordinates/session-envelope
     {:session-id session-id
      :run-id run-id
      :runtime-description (:runtime/description runtime)
      :context-description (:context/description runtime)
      :project project
      :provider provider
      :endpoint endpoint
      :model model
      :reasoning-effort reasoning-effort
      :allow-insecure-http allow-insecure-http
      :system-prompt system-prompt})))

(defn- append-bb4t! [event-store session-id event]
  (store/append-event! event-store session-id {:event/type :bb4t/event
                                               :bb4t/event event}))

(defn- subscribe-after-snapshot! [runtime event-store session-id]
  (let [{:keys [events events/dropped]} (bb4t/snapshot runtime)]
    (when (pos? (or dropped 0))
      (store/append-event! event-store session-id
                           {:event/type :bb4t/events-dropped
                            :events/dropped dropped}))
    (doseq [event events]
      (append-bb4t! event-store session-id event)))
  (bb4t/subscribe! runtime #(append-bb4t! event-store session-id %)))

(defn checkpoint! [^AgentSession session reason]
  (append-session-event! session
                         {:event/type :session/checkpoint
                          :checkpoint/reason reason
                          :session/messages @(:messages session)
                          :repl/replay-forms @(:replay-forms session)}))

(defn start!
  [{:keys [state-root project-root model-provider system-prompt session-id
           store-backend]
    :or {session-id (coordinates/new-session-id) store-backend :file}}]
  (let [run-id (coordinates/new-run-id)
        project (coordinates/project-description project-root)
        event-store (storage/open! state-root store-backend)
        unsubscribe (atom nil)]
    (try
      (let [runtime (bb4t/create (:project/root project))
            coordinate (envelope session-id run-id project model-provider
                                 system-prompt runtime)
            _ (store/append-event!
               event-store session-id
               {:event/type :session/started
                :session/coordinate coordinate
                :provider/config
                (:description (provider-coordinate model-provider))})
             subscription (subscribe-after-snapshot! runtime event-store
                                                     session-id)
             _ (reset! unsubscribe subscription)
             session (->AgentSession session-id run-id project model-provider
                                     system-prompt event-store runtime
                                     coordinate (atom []) (atom []) subscription
                                     (atom false))]
        (checkpoint! session :session-start)
        session)
      (catch Throwable failure
        (when-let [cancel @unsubscribe] (cancel))
        (store/close-store! event-store)
        (throw failure)))))

(defn- recovery-state
  "Rebuilds messages and replay forms from the event tail after the given
   checkpoint, seeded with the checkpoint's durable state.  request-event
   is an indexed lookup (fn [request-id]) used when a tail :repl/result's
   request predates the checkpoint."
  [checkpoint tail request-event]
  (let [requests (into {} (keep (fn [event]
                                  (when (= :repl/request (:event/type event))
                                    [(:request/id event) event]))) tail)
        results (set (keep (fn [event]
                             (when (= :repl/result (:event/type event))
                               (:request/id event))) tail))
        unresolved (seq (remove #(contains? results (:request/id %))
                                (filter #(= :repl/request (:event/type %)) tail)))
        action-messages
        (into {}
              (mapcat (fn [event]
                        (when (and (= :model/response (:event/type event))
                                   (= :ok (:response/status event)))
                          (let [message (get-in event [:model/response :message])]
                            (map (fn [entry] [(:action/id entry) message])
                                 (:actions message))))))
              tail)]
    (when unresolved
      (throw (errors/error :session-recovery-failure
                           "A REPL effect was interrupted before its result was durable"
                           {:request/ids (mapv :request/id unresolved)})))
    (reduce
     (fn [{:keys [messages replay-forms] :as state} event]
       (case (:event/type event)
         :user/message
         (assoc state :messages
                (conj messages {:role :user :content (:message/content event)}))

         :agent/action
         (if (= :finish (get-in event [:agent/action :action/type]))
           (assoc state :messages
                  (conj messages {:role :assistant
                                  :content (get-in event
                                                   [:agent/action :message])}))
           state)

         :repl/result
         (let [request (or (get requests (:request/id event))
                           (request-event (:request/id event)))
                _ (when-not (= :repl/request (:event/type request))
                    (throw (errors/error
                            :session-recovery-failure
                            "A durable REPL result has no replayable request"
                            {:request/id (:request/id event)})))
                _ (when-not (= (:action/id request) (:action/id event))
                    (throw (errors/error
                            :session-recovery-failure
                            "A durable REPL result has mismatched action identity"
                            {:request/id (:request/id event)
                             :request/action-id (:action/id request)
                             :result/action-id (:action/id event)})))
               action-id (:action/id event)
               result (:repl/result event)
               assistant-message
               (or (get action-messages action-id)
                   {:role :assistant
                    :content nil
                    :actions [{:action/id action-id
                               :action/value
                               {:action/type :repl/eval
                                :source (:repl/source request)}}]})]
           (-> state
               (assoc :messages
                      (conj messages
                            assistant-message
                            {:role :tool
                             :action/id action-id
                             :content (pr-str result)}))
               (assoc :replay-forms
                      (conj replay-forms
                            {:source (:repl/source request)
                             :expected-status (:status result)}))))

         state))
     {:messages (vec (:session/messages checkpoint))
      :replay-forms (vec (:repl/replay-forms checkpoint))}
     tail)))

(defn resume!
  [{:keys [state-root session-id model-provider system-prompt store-backend]
    :or {store-backend :file}}]
  (let [event-store (storage/open! state-root store-backend)]
    (try
      (let [_ (store/validate-session! event-store session-id)
            started (or (store/first-event event-store session-id
                                           :session/started)
                        (throw (errors/error :session-recovery-failure
                                             "Session has no start event"
                                             {:session/id session-id})))
            checkpoint (or (store/latest-checkpoint event-store session-id)
                           (throw (errors/error :session-recovery-failure
                                                "Session has no checkpoint"
                                                {:session/id session-id})))
            tail (store/events-after event-store session-id
                                     (:event/id checkpoint))
            project (:world (:session/coordinate started))
            current-project (coordinates/project-description
                             (:project/root project))
            run-id (coordinates/new-run-id)
            runtime (bb4t/create (:project/root project))
            {:keys [messages replay-forms]}
            (recovery-state checkpoint tail
                            #(store/request-event event-store session-id %))]
        (doseq [{:keys [source expected-status]} replay-forms]
          (let [result (bb4t/evaluate runtime source)]
            (when-not (= expected-status (:status result))
              (throw (errors/error :session-recovery-failure
                                   "A checkpoint form replay changed status"
                                   {:source source
                                    :expected-status expected-status
                                    :actual-status (:status result)})))))
        (let [coordinate (envelope session-id run-id
                                   current-project
                                   model-provider system-prompt runtime)
              _ (store/append-event!
                 event-store session-id
                 {:event/type :session/resumed
                  :session/coordinate coordinate
                  :resume/from-event (:event/id checkpoint)
                  :world/changed?
                  (not= (select-keys project
                                     [:project/revision :project/dirty?])
                        (select-keys current-project
                                     [:project/revision
                                      :project/dirty?]))
                  :provider/config
                  (:description
                   (provider-coordinate model-provider))})
              unsubscribe (subscribe-after-snapshot! runtime event-store
                                                     session-id)]
          (->AgentSession session-id run-id current-project model-provider
                          system-prompt event-store runtime coordinate
                          (atom messages)
                          (atom replay-forms) unsubscribe (atom false))))
      (catch clojure.lang.ExceptionInfo failure
        (store/close-store! event-store)
        (if (= :session-recovery-failure (:bbagent/error (ex-data failure)))
          (throw failure)
          (throw (errors/error :session-recovery-failure
                               "Session recovery failed"
                               {:session/id session-id} failure))))
      (catch Throwable failure
        (store/close-store! event-store)
        (throw (errors/error :session-recovery-failure
                             "Session recovery failed"
                             {:session/id session-id} failure))))))

(defn add-user-message! [^AgentSession session content]
  (let [message {:role :user :content content}]
    (append-session-event! session
                           {:event/type :user/message
                            :message/content content})
    (swap! (:messages session) conj message)
    message))

(defn request-model! [^AgentSession session]
  (let [request-id (str (UUID/randomUUID))
        recent (vec (take-last 40 @(:messages session)))
        recent (vec (drop-while #(= :tool (:role %)) recent))
        request {:request/id request-id
                 :messages (into [{:role :system
                                   :content (:system-prompt session)}]
                                 recent)}
        provider-config (provider/describe-provider (:provider session))]
    (append-session-event! session
                           {:event/type :model/request
                            :request/id request-id
                            :provider/config provider-config
                            :model/request request})
    (try
      (let [response (provider/complete (:provider session) request)]
        (append-session-event! session
                               {:event/type :model/response
                                :request/id request-id
                                :response/status :ok
                                :model/response response})
        response)
      (catch Throwable failure
        (append-session-event! session
                               {:event/type :model/response
                                :request/id request-id
                                :response/status :error
                                :error/category (:bbagent/error
                                                 (ex-data failure))
                                :error/message (.getMessage failure)
                                :error/data (:error/data (ex-data failure))})
        (throw failure)))))

(defn record-action! [^AgentSession session action-id action-value]
  (append-session-event! session
                         {:event/type :agent/action
                          :action/id action-id
                          :agent/action action-value}))

(defn evaluate! [^AgentSession session action-id source assistant-message]
  (let [request-id (str (UUID/randomUUID))]
    (swap! (:messages session) conj assistant-message)
    (append-session-event! session
                           {:event/type :repl/request
                            :request/id request-id
                            :action/id action-id
                            :repl/source source})
    (let [result (bb4t/evaluate (:bb4t session) source)]
      (append-session-event! session
                             {:event/type :repl/result
                              :request/id request-id
                              :action/id action-id
                              :repl/result result})
      (swap! (:replay-forms session) conj
             {:source source :expected-status (:status result)})
      (swap! (:messages session) conj
             {:role :tool
              :action/id action-id
              :content (pr-str result)})
      (checkpoint! session :repl-result)
      result)))

(defn finish! [^AgentSession session message]
  (swap! (:messages session) conj {:role :assistant :content message})
  (checkpoint! session :model-finish)
  message)

(defn close!
  ([session] (close! session :paused))
  ([^AgentSession session reason]
   (when (compare-and-set! (:closed session) false true)
     (try
       (checkpoint! session reason)
       (append-session-event! session
                              {:event/type :session/ended
                               :session/end-reason reason
                               :run/id (:run-id session)})
       (finally
         (try
           ((:unsubscribe session))
           (finally
             (store/close-store! (:store session)))))))
   nil))

(defn session-events [^AgentSession session]
  (store/events (:store session) (:session-id session)))
