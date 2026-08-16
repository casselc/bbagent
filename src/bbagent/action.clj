(ns bbagent.action
  (:require [bbagent.errors :as errors]
            [clojure.string :as str]))

(defn normalize [candidate]
  (let [action-type (or (:action/type candidate)
                        (some-> (:type candidate) keyword))]
    (case action-type
      :repl/eval
      (let [source (:source candidate)]
        (when-not (and (string? source) (not (str/blank? source)))
          (throw (errors/error :agent-invalid-action
                               "REPL action requires non-empty source"
                               {:candidate candidate})))
        {:action/type :repl/eval :source source})

      :finish
      (let [message (:message candidate)]
        (when-not (string? message)
          (throw (errors/error :agent-invalid-action
                               "Finish action requires a message"
                               {:candidate candidate})))
        {:action/type :finish :message message})

      (throw (errors/error :agent-invalid-action "Unknown agent action"
                           {:candidate candidate})))))
