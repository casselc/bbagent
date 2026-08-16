(ns bbagent.agent-test
  (:require [bbagent.agent :as agent]
            [bbagent.bb4t :as bb4t]
            [bbagent.journal :as journal]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files Path]))

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

(deftest fake-provider-end-to-end-test
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
                                       :system-prompt "test prompt"})]
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
      (finally (session/close! agent-session :test-end)))))

(deftest restart-resume-test
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
                         :system-prompt "test prompt"})
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
            :system-prompt "test prompt"})]
      (try
        (is (= session-id (:session-id second-session)))
        (is (= "The resumed value is 42."
               (agent/turn! second-session "Continue.")))
        (is (= 42
               (->> (session/session-events second-session)
                    (filter #(= :repl/result (:event/type %)))
                    last :repl/result :evaluation :value :value/data)))
        (finally (session/close! second-session :test-end))))))

(deftest failed-form-mutations-are-replayed-test
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
          :system-prompt "test prompt"})
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
            :system-prompt "test prompt"})]
      (try
        (is (= "Partial state recovered."
               (agent/turn! second-session "Check the partial state.")))
        (is (= 42
               (->> (session/session-events second-session)
                    (filter #(= :repl/result (:event/type %)))
                    last :repl/result :evaluation :value :value/data)))
        (finally (session/close! second-session :test-end))))))

(deftest durable-result-tail-is-folded-on-resume-test
  (let [state-root (temp-root "bbagent-tail-state")
        project-root (project)
        first-session
        (session/start! {:state-root state-root
                         :project-root project-root
                         :model-provider (provider/fake [])
                         :system-prompt "test prompt"})
        session-id (:session-id first-session)
        request-id "tail-request"
        action-id "tail-action"
        source "(def tail-value 9)"
        result (bb4t/evaluate (:bb4t first-session) source)]
    (journal/append! (:journal first-session)
                     {:event/type :repl/request
                      :request/id request-id
                      :action/id action-id
                      :repl/source source})
    (journal/append! (:journal first-session)
                     {:event/type :repl/result
                      :request/id request-id
                      :action/id action-id
                      :repl/result result})
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
            :system-prompt "test prompt"})]
      (try
        (is (= "Tail state recovered."
               (agent/turn! second-session "Check the tail.")))
        (is (= 10
               (->> (session/session-events second-session)
                    (filter #(= :repl/result (:event/type %)))
                    last :repl/result :evaluation :value :value/data)))
        (finally
          (session/close! second-session :test-end)
          ((:unsubscribe first-session)))))))
