#!/usr/bin/env clojure -M
(ns a2-live-replay
  "A2 live replay proof: a model edits a project, the process exits, and a
   second process resumes and keeps working.

  The deterministic tests and the native gates drive recovery from forms the
  harness chose. This drives it from forms a model chose, across a real process
  boundary, which is the scenario the milestone is actually about: an operator
  stops for the day and picks the session back up.

  Two phases, run as two processes:

    edit    start a session, ask the model to change a file, exit
    resume  reopen that session and ask a question that can only be answered
            from what the first process computed

  Usage:
    clojure -M script/a2-live-replay.clj edit   STATE PROJECT SESSION ENDPOINT MODEL
    clojure -M script/a2-live-replay.clj resume STATE SESSION ENDPOINT MODEL"
  (:require [bbagent.agent :as agent]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

(defn- system-prompt [] (slurp (io/resource "bbagent/system.txt")))

(defn- model-provider [endpoint model]
  (provider/openai-compatible {:endpoint endpoint :model model
                              :api-key "lemonade-local"
                              :allow-insecure-http true}))

(defn- observations [state-root session-id]
  (let [root (storage/open! state-root :sqlite)]
    (try
      (let [events (store/events root session-id)
            requests (filter #(= :repl/request (:event/type %)) events)
            results (filter #(= :repl/result (:event/type %)) events)]
        {:repl/sources (mapv :repl/source requests)
         :repl/statuses (frequencies (map #(get-in % [:repl/result :status])
                                          results))
         :repl/operations (mapv (fn [event]
                                  (mapv (juxt :operation/id :status)
                                        (:repl/operations event)))
                                results)
         :session/replay (->> events
                              (filter #(= :session/resumed (:event/type %)))
                              last
                              :session/replay)})
      (finally (store/close-store! root)))))

(defn- turn [agent-session message]
  (try {:status :finished :message (agent/turn! agent-session message)}
       (catch clojure.lang.ExceptionInfo failure
         {:status :failed :category (:bbagent/error (ex-data failure))
          :message (ex-message failure)})))

(defn edit! [state-root project-root session-id endpoint model]
  (let [s (session/start! {:state-root state-root
                           :project-root project-root
                           :model-provider (model-provider endpoint model)
                           :system-prompt (system-prompt)
                           :session-id session-id
                           :store-backend :sqlite})
        outcome (turn s (str "In src/quarry/lattice.clj, change the value of "
                             "spacing from 7 to 11. Change nothing else. "
                             "Retain the edit's result with "
                             "(def applied ...) so a later session can see it."))]
    (session/close! s :live-replay-edit)
    (merge {:phase :edit :outcome outcome
            :file/content (slurp (io/file project-root "src/quarry/lattice.clj"))}
           (observations state-root session-id))))

(defn resume! [state-root session-id endpoint model]
  (let [s (session/resume! {:state-root state-root
                            :session-id session-id
                            :model-provider (model-provider endpoint model)
                            :system-prompt (system-prompt)
                            :store-backend :sqlite})
        project-root (:project/root (:project s))
        outcome (turn s (str "Without reading or editing any file, tell me the "
                             "value of (:bytes applied), which you defined "
                             "before this session was interrupted."))]
    (session/close! s :live-replay-resume)
    (merge {:phase :resume :outcome outcome
            :file/content (slurp (io/file project-root "src/quarry/lattice.clj"))}
           (observations state-root session-id))))

(defn -main [& [phase & args]]
  (let [result (case phase
                 "edit" (let [[state project session endpoint model] args]
                          (edit! state project session endpoint model))
                 "resume" (let [[state session endpoint model] args]
                            (resume! state session endpoint model))
                 (throw (ex-info "Unknown phase" {:phase phase})))]
    (pprint/pprint result)))

;; `clojure -M script/a2-live-replay.clj ARGS` loads this file; it does not
;; call -main by itself.
(apply -main *command-line-args*)
