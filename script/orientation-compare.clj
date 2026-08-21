#!/usr/bin/env clojure -M
(ns orientation-compare
  "A1.1 variant comparison.

  Runs the same prompt against the same model under each orientation variant
  and reports, from the durable journal alone, what the model actually did.

  The measured question is not answer quality. It is whether the model
  discovers the surface it already has, and whether it reaches a correct
  conclusion about a capability it does not have instead of guessing.

  Usage:
    clojure -M script/orientation-compare.clj STATE_ROOT PROJECT ENDPOINT MODEL \\
        REPETITIONS PROMPT"
  (:require [bbagent.agent :as agent]
            [bbagent.orientation :as orientation]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def variants [:none :minimal :generated :grounded])

(def ^:private discovery-pattern #"\(\s*(apropos|doc)\b")

(defn- system-prompt []
  (slurp (io/resource "bbagent/system.txt")))

(defn- run-once!
  "Runs one prompt under one variant in a fresh session and returns the
   observation, derived from the journal rather than from the return value."
  [{:keys [state-root project-root endpoint model prompt variant index]}]
  (let [session-id (format "a11-%s-%02d" (name variant) index)
        model-provider (provider/openai-compatible
                        {:endpoint endpoint
                         :model model
                         :api-key "lemonade-local"
                         :allow-insecure-http true})
        started (System/currentTimeMillis)
        agent-session (session/start! {:state-root state-root
                                       :project-root project-root
                                       :model-provider model-provider
                                       :system-prompt (system-prompt)
                                       :session-id session-id
                                       :store-backend :sqlite
                                       :orientation variant})
        outcome (try
                  {:status :finished :message (agent/turn! agent-session prompt)}
                  (catch clojure.lang.ExceptionInfo failure
                    {:status :failed
                     :category (:bbagent/error (ex-data failure))
                     :message (ex-message failure)})
                  (catch Throwable failure
                    {:status :failed
                     :category :unclassified
                     :message (str failure)}))
        elapsed (- (System/currentTimeMillis) started)]
    (session/close! agent-session :experiment-end)
    (let [root (storage/open! state-root :sqlite)]
      (try
        (let [events (store/events root session-id)
              requests (filter #(= :repl/request (:event/type %)) events)
              results (filter #(= :repl/result (:event/type %)) events)
              sources (mapv :repl/source requests)
              statuses (frequencies (map #(get-in % [:repl/result :status])
                                         results))]
          (merge outcome
                 {:variant variant
                  :index index
                  :session/id session-id
                  :elapsed/ms elapsed
                  :repl/attempts (count requests)
                  :repl/statuses statuses
                  :repl/errors (get statuses :error 0)
                  :repl/sources sources
                  :discovery/used?
                  (boolean (some #(re-find discovery-pattern (str %)) sources))
                  :orientation/recorded
                  (get-in (store/first-event root session-id :session/started)
                          [:session/coordinate :prompt :orientation])}))
        (finally (store/close-store! root))))))

(defn- concludes-limitation?
  "A correct answer to an impossible request names the missing capability
   rather than inventing one. Judged on the model's own final message."
  [message]
  (let [text (str/lower-case (str message))]
    (boolean
     (and (re-find #"cannot|can't|unable|no (way|operation|capability)|not (able|available)|don't have|do not have"
                   text)
          (re-find #"list|enumerate|directory|directories|browse|discover|contents"
                   text)))))

(defn- summarize [observations]
  (let [n (count observations)
        finished (count (filter #(= :finished (:status %)) observations))
        errors (map :repl/errors observations)
        attempts (map :repl/attempts observations)]
    {:runs n
     :finished finished
     :failed (- n finished)
     :repl/attempts-mean (when (pos? n) (double (/ (reduce + attempts) n)))
     :repl/errors-mean (when (pos? n) (double (/ (reduce + errors) n)))
     :discovery/used (count (filter :discovery/used? observations))
     :concluded-limitation
     (count (filter #(and (= :finished (:status %))
                          (concludes-limitation? (:message %)))
                    observations))
     :elapsed/mean-ms (when (pos? n)
                        (double (/ (reduce + (map :elapsed/ms observations)) n)))}))

(defn -main [& [state-root project-root endpoint model repetitions & prompt]]
  (let [reps (parse-long (or repetitions "3"))
        prompt (str/join " " prompt)
        _ (println "prompt:" (pr-str prompt))
        _ (println "model:" model "| repetitions per variant:" reps)
        observations
        (doall
         (for [variant variants
               index (range 1 (inc reps))]
           (let [o (run-once! {:state-root state-root
                               :project-root project-root
                               :endpoint endpoint
                               :model model
                               :prompt prompt
                               :variant variant
                               :index index})]
             (println (format "  %-10s %2d  %-9s attempts %2d  errors %2d  discovery %-5s  %6d ms"
                              (name variant) index (name (:status o))
                              (:repl/attempts o) (:repl/errors o)
                              (str (:discovery/used? o)) (:elapsed/ms o)))
             (flush)
             o)))]
    (println)
    (println "=== summary ===")
    (doseq [variant variants]
      (println variant
               (pr-str (summarize (filter #(= variant (:variant %)) observations)))))
    (println)
    (println "=== observations ===")
    (spit "orientation-observations.edn"
          (with-out-str (pprint/pprint (vec observations))))
    (println "written to orientation-observations.edn")))
