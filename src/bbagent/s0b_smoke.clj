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

(def ^:private replay-file "replay-proof.txt")

(defn- replay-evidence [agent-session project-root]
  (let [events (session/session-events agent-session)]
    {:session/id (:session-id agent-session)
     :run/id (:run-id agent-session)
     :replay/file-content (slurp (io/file project-root replay-file))
     :session/replay (->> events
                          (filter #(= :session/resumed (:event/type %)))
                          last
                          :session/replay)
     :repl/results (mapv (fn [event]
                           [(:repl/source
                             (first (filter #(and (= :repl/request
                                                     (:event/type %))
                                                  (= (:request/id event)
                                                     (:request/id %)))
                                            events)))
                            (get-in event [:repl/result :evaluation :value
                                           :value/data])])
                         (filter #(= :repl/result (:event/type %)) events))
     :repl/operations
     (mapv (fn [event]
             (mapv (juxt :operation/id :status)
                   (:repl/operations event)))
           (filter #(= :repl/result (:event/type %)) events))}))

(defn replay-create!
  "Creates a session that observes the project and then changes it.

   The native counterpart of the recovery tests: the point is what the second
   process does with this history, not what this one computes."
  [{:keys [state-root project-root session-id]}]
  (let [agent-session
        (session/start! {:state-root state-root
                         :project-root project-root
                         :session-id session-id
                         :store-backend :sqlite
                         :system-prompt prompt
                         :model-provider (provider/fake [])})]
    (try
      (spit (io/file project-root replay-file) "seed")
      (doseq [source [(str "(defn bump [path]"
                           "  (let [before (project/stat path)"
                           "        text (project/read path)]"
                           "    (project/edit {:path path"
                           "                   :base {:digest (:digest before)}"
                           "                   :content (str text \"!\")})))")
                      "(def observed (project/read \"README.md\"))"
                      (str "(def bumped (bump \"" replay-file "\"))")]]
        (let [result (session/operator-evaluate! agent-session source)]
          (when-not (= :ok (:status result))
            (throw (ex-info "Replay proof setup form failed"
                            {:source source :result result})))))
      (let [evidence (replay-evidence agent-session project-root)]
        (session/close! agent-session :s0b-native-replay-create)
        evidence)
      (catch Throwable failure
        (session/close! agent-session :s0b-native-replay-create-failed)
        (throw failure)))))

(defn replay-resume!
  "Resumes that session after the world has moved on.

   Reports what the reconstructed Context holds and what the file on disk now
   contains, so the harness can assert that the change was not made twice and
   that the observation was not silently refreshed."
  [{:keys [state-root session-id]}]
  (let [agent-session
        (session/resume! {:state-root state-root
                          :session-id session-id
                          :store-backend :sqlite
                          :system-prompt prompt
                          :model-provider (provider/fake [])})
        project-root (get-in (:project agent-session) [:project/root])]
    (try
      (doseq [source ["observed" "(:bytes bumped)"]]
        (let [result (session/operator-evaluate! agent-session source)]
          (when-not (= :ok (:status result))
            (throw (ex-info "Replay proof form failed"
                            {:source source :result result})))))
      (let [evidence (replay-evidence agent-session project-root)]
        (session/close! agent-session :s0b-native-replay-resume)
        evidence)
      (catch Throwable failure
        (session/close! agent-session :s0b-native-replay-resume-failed)
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
        bytes (.getBytes ^String uncommitted-content StandardCharsets/UTF_8)
        reference (tagged-literal 'bbagent/blob
                                  {:digest (str "sha256:" digest)
                                   :bytes (alength bytes)
                                   :encoding :utf-8})
        event (store/prepare-event
               {:event/id "s0b-transaction-uncommitted-event"
                :event/time "2026-08-18T00:00:00Z"
                :event/type :s0b/uncommitted-object
                :object/content reference}
               2)
        payload (.getBytes ^String (store/encode-payload event)
                           StandardCharsets/UTF_8)]
    (store/append-event! root-store session-id
                         {:event/id "s0b-transaction-baseline"
                          :event/type :session/started})
    (jdbc/execute-one! (:connection root-store) ["BEGIN IMMEDIATE"])
    (jdbc/execute-one!
     (:connection root-store)
     ["INSERT INTO object (digest, bytes, encoding, media_type, content)
       VALUES (?, ?, ?, ?, ?)"
      digest (alength bytes) "utf-8" nil bytes])
    (jdbc/execute-one!
     (:connection root-store)
     ["INSERT INTO event
       (session_id, seq, event_id, event_type, event_time, request_id,
        action_id, payload, checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
      session-id 2 (:event/id event) "s0b/uncommitted-object"
      (:event/time event) nil nil payload (store/semantic-checksum event)])
    (prn {:session/id session-id
          :stage :uncommitted-object-and-event-inserted
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
