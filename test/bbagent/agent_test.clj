(ns bbagent.agent-test
  (:require [bbagent.agent :as agent]
            [bbagent.bb4t :as bb4t]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files LinkOption Path Paths]))

(def ^:private backends [:file :sqlite])

(defn- temp-root [prefix]
  (str (Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- project []
  (let [^Path root (Files/createTempDirectory
                    "bbagent-agent-project"
                    (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString (.resolve root "README.md") "A tiny fixture project."
                       (make-array java.nio.file.OpenOption 0))
    (str root)))

(defn- path-exists? [root child]
  (Files/exists (.resolve (Paths/get root (make-array String 0)) child)
                (make-array LinkOption 0)))

(defn- error-category [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo failure
         (:bbagent/error (ex-data failure)))))

(deftest store-backend-selection-test
  (testing "an unspecified backend selects sqlite"
    (is (= :sqlite (storage/backend nil))))
  (testing "backend values normalize to :file or :sqlite"
    (is (= :file (storage/backend :file)))
    (is (= :file (storage/backend "file")))
    (is (= :sqlite (storage/backend :sqlite)))
    (is (= :sqlite (storage/backend "sqlite"))))
  (testing "other values are rejected clearly"
    (is (= :journal-storage-failure (error-category #(storage/backend :memory))))
    (is (= :journal-storage-failure (error-category #(storage/backend "memory"))))
    (is (= :journal-storage-failure (error-category #(storage/backend 42))))))

(deftest store-backend-artifacts-test
  (testing "a new session defaults to sqlite"
    (let [state-root (temp-root "bbagent-default-state")
          agent-session (session/start! {:state-root state-root
                                         :project-root (project)
                                         :model-provider (provider/fake [])
                                         :system-prompt "test prompt"})]
      (try
        (is (path-exists? state-root "bbagent.sqlite3"))
        (is (not (path-exists? state-root "sessions"))
            "the default new session writes no file-backend journal")
        (finally (session/close! agent-session :test-end)))))
  (testing "file remains explicitly selectable"
    (let [state-root (temp-root "bbagent-explicit-file-state")
          agent-session (session/start! {:state-root state-root
                                         :project-root (project)
                                         :model-provider (provider/fake [])
                                         :system-prompt "test prompt"
                                         :store-backend :file})]
      (try
        (is (path-exists? state-root "sessions"))
        (is (not (path-exists? state-root "bbagent.sqlite3")))
        (finally (session/close! agent-session :test-end)))))
  (testing "sqlite keeps the database at state-root/bbagent.sqlite3"
    (let [state-root (temp-root "bbagent-sqlite-state")
          agent-session (session/start! {:state-root state-root
                                         :project-root (project)
                                         :model-provider (provider/fake [])
                                         :system-prompt "test prompt"
                                         :store-backend :sqlite})]
      (try
        (is (path-exists? state-root "bbagent.sqlite3"))
        (finally (session/close! agent-session :test-end))))))

(deftest existing-session-backend-identity-test
  (testing "an existing file session is never reinterpreted by the new default"
    (let [state-root (temp-root "bbagent-backend-identity")
          session-id "backend-identity-session"
          created (session/start! {:state-root state-root
                                   :project-root (project)
                                   :model-provider (provider/fake [])
                                   :system-prompt "test prompt"
                                   :session-id session-id
                                   :store-backend :file})]
      (session/close! created :test-end)
      (testing "the default backend does not find the file session"
        (is (= :session-recovery-failure
               (error-category
                #(session/resume! {:state-root state-root
                                   :session-id session-id
                                   :model-provider (provider/fake [])
                                   :system-prompt "test prompt"})))))
      (testing "the file session's durable events are untouched"
        (let [file-store (storage/open! state-root :file)]
          (try
            (is (= [session-id] (store/list-sessions file-store)))
            (is (pos? (store/validate-session! file-store session-id)))
            (finally (store/close-store! file-store)))))
      (testing "no file session leaked into the sqlite store"
        (let [sqlite-store (storage/open! state-root :sqlite)]
          (try
            (is (= [] (store/list-sessions sqlite-store)))
            (finally (store/close-store! sqlite-store)))))
      (testing "explicit file selection resumes it"
        (let [resumed (session/resume! {:state-root state-root
                                        :session-id session-id
                                        :model-provider (provider/fake [])
                                        :system-prompt "test prompt"
                                        :store-backend :file})]
          (is (= session-id (:session-id resumed)))
          (session/close! resumed :test-end))))))

(deftest fake-provider-end-to-end-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-agent-state")
            model (provider/fake
                   [(provider/fake-response
                     {:action/type :repl/eval
                      :source "(def project-summary (project/read \"README.md\"))"})
                    (provider/fake-response
                     {:action/type :finish
                      :message "The fixture is described by README.md."})])
            agent-session (session/start! {:state-root state-root
                                           :project-root (project)
                                           :model-provider model
                                           :system-prompt "test prompt"
                                           :store-backend backend})]
        (try
          (is (= "The fixture is described by README.md."
                 (agent/turn! agent-session "What does this project do?")))
          (let [events (session/session-events agent-session)
                event-types (set (map :event/type events))]
            (is (every? event-types
                        [:session/started :user/message :model/request
                         :model/response :agent/action :repl/request
                         :repl/result :bb4t/event :session/checkpoint]))
            (is (= :ok (->> events
                            (filter #(= :repl/result (:event/type %)))
                            first :repl/result :status))))
          (finally (session/close! agent-session :test-end)))))))

(deftest restart-resume-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-resume-state")
            project-root (project)
            first-session
            (session/start! {:state-root state-root
                             :project-root project-root
                             :model-provider
                             (provider/fake
                              [(provider/fake-response
                                {:action/type :repl/eval
                                 :source "(def saved-value 41)"})
                               (provider/fake-response
                                {:action/type :finish :message "saved"})])
                             :system-prompt "test prompt"
                             :store-backend backend})
            session-id (:session-id first-session)]
        (is (= "saved" (agent/turn! first-session "Save a value.")))
        (session/close! first-session :restart-test)
        (let [second-session
              (session/resume!
               {:state-root state-root
                :session-id session-id
                :model-provider
                (provider/fake
                 [(provider/fake-response
                   {:action/type :repl/eval :source "(+ saved-value 1)"})
                  (provider/fake-response
                   {:action/type :finish :message "The resumed value is 42."})])
                :system-prompt "test prompt"
                :store-backend backend})]
          (try
            (is (= session-id (:session-id second-session)))
            (is (= "The resumed value is 42."
                   (agent/turn! second-session "Continue.")))
            (is (= 42
                   (->> (session/session-events second-session)
                        (filter #(= :repl/result (:event/type %)))
                        last :repl/result :evaluation :value :value/data)))
            (finally (session/close! second-session :test-end))))))))

(deftest failed-form-mutations-are-replayed-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-failed-replay-state")
            project-root (project)
            first-session
            (session/start!
             {:state-root state-root
              :project-root project-root
              :model-provider
              (provider/fake
               [(provider/fake-response
                 {:action/type :repl/eval
                  :source "(do (def survived 41) (/ 1 0))"})
                (provider/fake-response
                 {:action/type :finish :message "failure observed"})])
              :system-prompt "test prompt"
              :store-backend backend})
            session-id (:session-id first-session)]
        (is (= "failure observed" (agent/turn! first-session "Try a partial form.")))
        (session/close! first-session :restart-test)
        (let [second-session
              (session/resume!
               {:state-root state-root
                :session-id session-id
                :model-provider
                (provider/fake
                 [(provider/fake-response
                   {:action/type :repl/eval :source "(+ survived 1)"})
                  (provider/fake-response
                   {:action/type :finish :message "Partial state recovered."})])
                :system-prompt "test prompt"
                :store-backend backend})]
          (try
            (is (= "Partial state recovered."
                   (agent/turn! second-session "Check the partial state.")))
            (is (= 42
                   (->> (session/session-events second-session)
                        (filter #(= :repl/result (:event/type %)))
                        last :repl/result :evaluation :value :value/data)))
            (finally (session/close! second-session :test-end))))))))

(deftest durable-result-tail-is-folded-on-resume-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-tail-state")
            project-root (project)
            first-session
            (session/start! {:state-root state-root
                             :project-root project-root
                             :model-provider (provider/fake [])
                             :system-prompt "test prompt"
                             :store-backend backend})
            session-id (:session-id first-session)
            request-id "tail-request"
            action-id "tail-action"
            source "(def tail-value 9)"
            result (bb4t/evaluate (:bb4t first-session) source)]
        (store/append-event! (:store first-session) session-id
                             {:event/type :repl/request
                              :request/id request-id
                              :action/id action-id
                              :repl/source source})
        (store/append-event! (:store first-session) session-id
                             {:event/type :repl/result
                              :request/id request-id
                              :action/id action-id
                              :repl/result result})
        ((:unsubscribe first-session))
        (store/close-store! (:store first-session))
        (let [second-session
              (session/resume!
               {:state-root state-root
                :session-id session-id
                :model-provider
                (provider/fake
                 [(provider/fake-response
                   {:action/type :repl/eval :source "(+ tail-value 1)"})
                  (provider/fake-response
                   {:action/type :finish :message "Tail state recovered."})])
                :system-prompt "test prompt"
                :store-backend backend})]
          (try
            (let [[assistant-message tool-message] @(:messages second-session)]
              (is (= "tail-action"
                     (get-in assistant-message [:actions 0 :action/id])))
              (is (= "tail-action" (:action/id tool-message))))
            (is (= "Tail state recovered."
                   (agent/turn! second-session "Check the tail.")))
            (is (= 10
                   (->> (session/session-events second-session)
                        (filter #(= :repl/result (:event/type %)))
                        last :repl/result :evaluation :value :value/data)))
            (finally
              (session/close! second-session :test-end))))))))

(deftest unresolved-tail-request-fails-recovery-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-unresolved-state")
            first-session
            (session/start! {:state-root state-root
                             :project-root (project)
                             :model-provider (provider/fake [])
                             :system-prompt "test prompt"
                             :store-backend backend})
            session-id (:session-id first-session)]
        (store/append-event! (:store first-session) session-id
                             {:event/type :repl/request
                              :request/id "interrupted-request"
                              :action/id "interrupted-action"
                              :repl/source "(def interrupted 1)"})
        ((:unsubscribe first-session))
        (store/close-store! (:store first-session))
        (is (= :session-recovery-failure
               (error-category
                #(session/resume! {:state-root state-root
                                    :session-id session-id
                                    :model-provider (provider/fake [])
                                    :system-prompt "test prompt"
                                    :store-backend backend}))))))))

(deftest checkpoint-cannot-hide-unresolved-repl-effect-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-checkpoint-ambiguity-state")
            first-session
            (session/start! {:state-root state-root
                             :project-root (project)
                             :model-provider (provider/fake [])
                             :system-prompt "test prompt"
                             :store-backend backend})
            session-id (:session-id first-session)]
        (store/append-event! (:store first-session) session-id
                             {:event/type :repl/request
                              :request/id "checkpoint-interrupted-request"
                              :action/id "checkpoint-interrupted-action"
                              :repl/source "(def interrupted 1)"})
        (session/checkpoint! first-session :after-interrupted-request)
        (session/close! first-session :test-end)
        (let [failure
              (try
                (session/resume! {:state-root state-root
                                  :session-id session-id
                                  :model-provider (provider/fake [])
                                  :system-prompt "test prompt"
                                  :store-backend backend})
                nil
                (catch clojure.lang.ExceptionInfo failure failure))]
          (is (= :session-recovery-failure
                 (:bbagent/error (ex-data failure))))
          (is (= [{:effect/type :repl
                   :request/id "checkpoint-interrupted-request"
                   :action/id "checkpoint-interrupted-action"}]
                 (mapv #(select-keys % [:effect/type :request/id :action/id])
                       (get-in (ex-data failure) [:error/data :effects])))))))))

(deftest successful-provider-persistence-failure-is-not-provider-error-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-provider-persistence-state")
            completions (atom 0)
            model (provider/fake
                   [(fn [_]
                      (swap! completions inc)
                      (provider/fake-response
                       {:action/type :finish :message "completed"}))])
            first-session
            (session/start! {:state-root state-root
                             :project-root (project)
                             :model-provider model
                             :system-prompt "test prompt"
                             :store-backend backend})
            session-id (:session-id first-session)
            append-session-event! @#'bbagent.session/append-session-event!
            failed? (atom false)
            failure
            (try
              (with-redefs-fn
                {#'bbagent.session/append-session-event!
                 (fn [agent-session event]
                   (if (and (= :model/response (:event/type event))
                            (= :ok (:response/status event))
                            (compare-and-set! failed? false true))
                     (throw (ex-info "injected response persistence failure"
                                     {:bbagent/error :journal-storage-failure}))
                     (append-session-event! agent-session event)))}
                #(session/request-model! first-session))
              nil
              (catch clojure.lang.ExceptionInfo failure failure))
            events (session/session-events first-session)]
        (is (= 1 @completions))
        (is (= :journal-storage-failure (:bbagent/error (ex-data failure))))
        (is (= [:model/request]
               (->> events
                    (filter #(#{:model/request :model/response} (:event/type %)))
                    (mapv :event/type))))
        (is (empty? (filter #(and (= :model/response (:event/type %))
                                  (= :error (:response/status %)))
                            events)))
        (session/checkpoint! first-session :after-provider-persistence-failure)
        (session/close! first-session :test-end)
        (let [recovery-failure
              (try
                (session/resume! {:state-root state-root
                                  :session-id session-id
                                  :model-provider (provider/fake [])
                                  :system-prompt "test prompt"
                                  :store-backend backend})
                nil
                (catch clojure.lang.ExceptionInfo failure failure))]
          (is (= :session-recovery-failure
                 (:bbagent/error (ex-data recovery-failure))))
          (is (= [:model]
                 (mapv :effect/type
                       (get-in (ex-data recovery-failure)
                               [:error/data :effects])))))))))

(deftest coordinate-preservation-test
  (doseq [backend backends]
    (testing (str "backend " (name backend))
      (let [state-root (temp-root "bbagent-coordinate-state")
            project-root (project)
            first-session
            (session/start! {:state-root state-root
                             :project-root project-root
                             :model-provider (provider/fake [])
                             :system-prompt "test prompt"
                             :store-backend backend})
            session-id (:session-id first-session)
            started-coordinate (:coordinate first-session)]
        (is (= session-id (:session/id started-coordinate)))
        (is (= :fake (get-in started-coordinate [:model :provider])))
        (is (= {:kind :persistent-sci :version 1}
               (:surface started-coordinate)))
        (session/close! first-session :restart-test)
        (let [second-session
              (session/resume! {:state-root state-root
                                :session-id session-id
                                :model-provider (provider/fake [])
                                :system-prompt "test prompt"
                                :store-backend backend})]
          (try
            (is (= session-id (get-in second-session [:coordinate :session/id])))
            (is (= (get-in started-coordinate [:world :project/root])
                   (get-in second-session [:coordinate :world :project/root])))
            (is (= (:surface started-coordinate)
                   (get-in second-session [:coordinate :surface])))
            (let [resumed (last (filter #(= :session/resumed (:event/type %))
                                        (session/session-events
                                         second-session)))]
              (is (some? resumed))
              (is (false? (:world/changed? resumed)))
              (is (= (:session/id (:session/coordinate resumed)) session-id)))
            (finally (session/close! second-session :test-end))))))))

(deftest openai-agent-tool-correlation-test
  (let [state-root (temp-root "bbagent-openai-state")
        requests (atom [])
        responses
        (atom [{:id "response-1"
                :model "model"
                :choices [{:finish_reason "tool_calls"
                           :message {:content nil
                                     :tool_calls
                                     [{:id "call-1"
                                       :type "function"
                                       :function
                                       {:name "repl_eval"
                                        :arguments "{\"source\":\"(+ 1 2)\"}"}}]}}]}
               {:id "response-2"
                :model "model"
                :choices [{:finish_reason "stop"
                           :message {:content "three"}}]}])
        model (provider/openai-compatible
               {:endpoint "https://example.test/v1/chat/completions"
                :model "model" :api-key "not-journaled"})
        agent-session (session/start! {:state-root state-root
                                       :project-root (project)
                                       :model-provider model
                                       :system-prompt "test prompt"})]
    (try
      (with-redefs [http/post
                    (fn [_ options]
                      (swap! requests conj (json/parse-string (:body options) true))
                      (let [response (first @responses)]
                        (swap! responses #(vec (rest %)))
                        {:status 200 :body (json/generate-string response)}))]
        (is (= "three" (agent/turn! agent-session "Calculate.")))
        (let [second-messages (:messages (second @requests))]
          (is (= "call-1" (get-in second-messages [2 :tool_calls 0 :id])))
          (is (= "call-1" (get-in second-messages [3 :tool_call_id])))))
      (finally (session/close! agent-session :test-end)))))
