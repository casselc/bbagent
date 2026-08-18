(ns bbagent.s0b-smoke
  (:require [bbagent.agent :as agent]
            [bbagent.coordinates :as coordinates]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc])
  (:import [java.nio.charset StandardCharsets]))

(def ^:private prompt "S0b deterministic native storage proof.")
(def ^:private uncommitted-content "S0b uncommitted crash probe object.")

(defn- evidence [state-root session-id run-id answer]
  (let [root-store (storage/open! state-root :sqlite)]
    (try
      (let [events (store/events root-store session-id)
            started (first (filter #(= :session/started (:event/type %)) events))
            project-root (get-in started [:session/coordinate :world :project/root])
            content (slurp (io/file project-root "README.md"))
            object-digest (str "sha256:" (coordinates/sha-256 content))
            object-required? (> (alength (.getBytes ^String content
                                                   StandardCharsets/UTF_8))
                                store/blob-threshold-bytes)
            checkpoint (store/latest-checkpoint root-store session-id)
            results (filterv #(= :repl/result (:event/type %)) events)]
        {:session/id session-id
         :run/id run-id
         :answer answer
         :event/count (count events)
         :event/last-seq (:event/seq (peek events))
         :event/types (mapv :event/type events)
         :checkpoint/id (:event/id checkpoint)
         :checkpoint/reason (:checkpoint/reason checkpoint)
         :repl/last-result (:repl/result (peek results))
         :object/digest object-digest
         :object/required? object-required?
         :object/verified? (or (not object-required?)
                               (= content (store/get-object root-store session-id
                                                            object-digest)))
         :session/valid-events (store/validate-session! root-store session-id)})
      (finally
        (store/close-store! root-store)))))

(defn create! [{:keys [state-root project-root session-id]}]
  (let [agent-session
        (session/start!
         {:state-root state-root
          :project-root project-root
          :session-id session-id
          :store-backend :sqlite
          :system-prompt prompt
          :model-provider
          (provider/fake
           [(provider/fake-response
             {:action/type :repl/eval
              :source "(project/read \"README.md\")"})
            (provider/fake-response
             {:action/type :repl/eval
              :source "(def saved-project (project/read \"README.md\"))"})
            (provider/fake-response
             {:action/type :finish :message "native SQLite session created"})])})
        run-id (:run-id agent-session)]
    (try
      (let [answer (agent/turn! agent-session "Read and retain the fixture.")]
        (store/append-event! (:store agent-session) session-id
                             {:event/type :s0b/native-object
                              :object/content
                              (slurp (io/file project-root "README.md"))})
        (session/close! agent-session :s0b-native-create)
        (evidence state-root session-id run-id answer))
      (catch Throwable failure
        (session/close! agent-session :s0b-native-create-failed)
        (throw failure)))))

(defn resume! [{:keys [state-root session-id]}]
  (let [agent-session
        (session/resume!
         {:state-root state-root
          :session-id session-id
          :store-backend :sqlite
          :system-prompt prompt
          :model-provider
          (provider/fake
           [(provider/fake-response
             {:action/type :repl/eval :source "(count saved-project)"})
            (provider/fake-response
             {:action/type :finish :message "native SQLite session resumed"})])})
        run-id (:run-id agent-session)]
    (try
      (let [answer (agent/turn! agent-session "Use the reconstructed value.")]
        (session/close! agent-session :s0b-native-resume)
        (evidence state-root session-id run-id answer))
      (catch Throwable failure
        (session/close! agent-session :s0b-native-resume-failed)
        (throw failure)))))

(defn ambiguous-exit! [{:keys [state-root project-root session-id]}]
  (let [agent-session
        (session/start! {:state-root state-root
                         :project-root project-root
                         :session-id session-id
                         :store-backend :sqlite
                         :system-prompt prompt
                         :model-provider (provider/fake [])})]
    (store/append-event! (:store agent-session) session-id
                         {:event/type :repl/request
                          :request/id "s0b-interrupted-request"
                          :action/id "s0b-interrupted-action"
                          :repl/source "(def should-not-replay 1)"})
    (prn {:session/id session-id
          :run/id (:run-id agent-session)
          :stage :request-intent-durable})
    (flush)
    ((:unsubscribe agent-session))
    (store/close-store! (:store agent-session))
    (.halt (Runtime/getRuntime) 73)))

(defn ambiguous-check! [{:keys [state-root session-id]}]
  (try
    (let [resumed (session/resume! {:state-root state-root
                                    :session-id session-id
                                    :store-backend :sqlite
                                    :system-prompt prompt
                                    :model-provider (provider/fake [])})]
      (session/close! resumed :unexpected-resume)
      {:recovery/status :unexpected-success})
    (catch clojure.lang.ExceptionInfo failure
      {:recovery/status :failed-closed
       :error/category (:bbagent/error (ex-data failure))
       :error/message (.getMessage failure)})))

(defn transaction-exit! [{:keys [state-root session-id]}]
  (let [root-store (storage/open! state-root :sqlite)
        digest (coordinates/sha-256 uncommitted-content)
        bytes (.getBytes ^String uncommitted-content StandardCharsets/UTF_8)]
    (store/append-event! root-store session-id
                         {:event/id "s0b-transaction-baseline"
                          :event/type :session/started})
    (jdbc/execute-one! (:connection root-store) ["BEGIN IMMEDIATE"])
    (jdbc/execute-one!
     (:connection root-store)
     ["INSERT INTO object (digest, bytes, encoding, media_type, content)
       VALUES (?, ?, ?, ?, ?)"
      digest (alength bytes) "utf-8" nil bytes])
    (prn {:session/id session-id
          :stage :uncommitted-object-inserted
          :object/digest (str "sha256:" digest)})
    (flush)
    (.halt (Runtime/getRuntime) 74)))

(defn transaction-check! [{:keys [state-root session-id]}]
  (let [root-store (storage/open! state-root :sqlite)
        digest (str "sha256:" (coordinates/sha-256 uncommitted-content))]
    (try
      {:session/id session-id
       :event/count (store/validate-session! root-store session-id)
       :uncommitted-object/visible?
       (try
         (store/get-object root-store session-id digest)
         true
         (catch clojure.lang.ExceptionInfo failure
           (if (= :session-recovery-failure (:bbagent/error (ex-data failure)))
             false
             (throw failure))))}
      (finally
        (store/close-store! root-store)))))
