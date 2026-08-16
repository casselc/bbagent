(ns bbagent.agent
  (:require [bbagent.errors :as errors]
            [bbagent.session :as session]))

(def ^:private max-actions-per-turn 12)

(defn turn! [agent-session user-message]
  (session/add-user-message! agent-session user-message)
  (loop [step 0]
    (when (>= step max-actions-per-turn)
      (throw (errors/error :agent-invalid-action
                           "Agent exceeded the A0 action limit"
                           {:limit max-actions-per-turn})))
    (let [response (session/request-model! agent-session)
          actions (:actions response)]
      (when-not (= 1 (count actions))
        (throw (errors/error :agent-invalid-action
                             "A0 requires exactly one action per model response"
                             {:action/count (count actions)})))
      (let [{:action/keys [id value]} (first actions)]
        (session/record-action! agent-session id value)
        (case (:action/type value)
          :repl/eval
          (do
            (session/evaluate! agent-session id (:source value)
                               (:message response))
            (recur (inc step)))

          :finish
          (session/finish! agent-session (:message value))

          (throw (errors/error :agent-invalid-action "Unsupported action"
                               {:action value})))))))
