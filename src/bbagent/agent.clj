(ns bbagent.agent
  (:require [bbagent.errors :as errors]
            [bbagent.session :as session]))

(def default-max-actions-per-turn
  "The per-turn action budget.

  A0 set this to 12 for a surface whose only observation was reading one named
  file. Granting project/list changed the shape of a turn: orienting in a real
  repository costs a listing per directory before any file is read, and the A2
  dogfood watched the model navigate correctly to the one file it needed and
  exhaust the budget on reaching it. The limit is a guard against a model
  looping, not a statement about how much looking a task deserves."
  40)

(defn turn!
  ([agent-session user-message]
   (turn! agent-session user-message default-max-actions-per-turn))
  ([agent-session user-message max-actions]
   (session/add-user-message! agent-session user-message)
   (loop [step 0]
     (when (>= step max-actions)
       (throw (errors/error :agent-invalid-action
                            "Agent exceeded the action limit"
                            {:limit max-actions})))
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
                                {:action value}))))))))
