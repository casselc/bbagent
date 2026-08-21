#!/usr/bin/env clojure -M
(ns a2-dogfood
  "A2 dogfood: does granting project/list change the answer?

  A1.1 ended with the model correctly reporting that it could not enumerate a
  directory. That is the right answer to an impossible request, and it is the
  before-half of this comparison. The after-half asks whether the same prompt,
  against a surface that can enumerate, produces a correct enumeration.

  The fixture is built to the requirement the A1.1 review derived: no file
  enumerates the others, and no filename is guessable. A1.1's fixture had a
  README naming the only other file, which let an unoriented model reconstruct
  the answer without enumerating and look accidentally correct.

  Usage:
    clojure -M script/a2-dogfood.clj STATE_ROOT ENDPOINT MODEL REPETITIONS"
  (:require [bbagent.agent :as agent]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def prompt "What files does this project contain, and what does each one do?")

(def ground-truth
  "Every file in the fixture. None is named by any other file's contents, so
   only enumeration can produce this set."
  #{"README.md" "src/quarry/lattice.clj" "tools/emit_manifest.clj"
    "config/thresholds.edn"})

(defn- fixture! []
  (let [^Path root (Files/createTempDirectory "a2-dogfood" (make-array FileAttribute 0))
        write (fn [rel content]
                (let [p (.resolve root ^String rel)]
                  (Files/createDirectories (.getParent p) (make-array FileAttribute 0))
                  (Files/writeString p ^String content
                                     (make-array java.nio.file.OpenOption 0))))]
    ;; Deliberately does not list the project's files.
    (write "README.md"
           (str "# Quarry\n\nA small batch pipeline. Build with the usual\n"
                "Clojure tooling. See the source for details.\n"))
    (write "src/quarry/lattice.clj"
           (str "(ns quarry.lattice)\n\n"
                ";; Builds the adjacency lattice used by the planner.\n"
                "(def spacing 7)\n(defn neighbours [n] (range n (+ n spacing)))\n"))
    (write "tools/emit_manifest.clj"
           (str "(ns emit-manifest)\n\n"
                ";; Emits the build manifest consumed by CI.\n"
                "(defn emit [target] {:target target :version 3})\n"))
    (write "config/thresholds.edn"
           "{:latency-ms 250 :retries 2 :checkpoint \"amber compass\"}\n")
    (str root)))

(defn- system-prompt [] (slurp (io/resource "bbagent/system.txt")))

(defn- run-arm! [{:keys [state-root endpoint model profile orientation index]}]
  (let [project-root (fixture!)
        session-id (format "a2-%s-%s-%02d" (name profile) (name orientation) index)
        model-provider (provider/openai-compatible
                        {:endpoint endpoint :model model
                         :api-key "lemonade-local" :allow-insecure-http true})
        started (System/currentTimeMillis)
        s (session/start! {:state-root state-root
                           :project-root project-root
                           :model-provider model-provider
                           :system-prompt (system-prompt)
                           :session-id session-id
                           :store-backend :sqlite
                           :profile profile
                           :orientation orientation})
        outcome (try {:status :finished :message (agent/turn! s prompt)}
                     (catch clojure.lang.ExceptionInfo f
                       {:status :failed :category (:bbagent/error (ex-data f))
                        :message (ex-message f)})
                     (catch Throwable f
                       {:status :failed :category :unclassified :message (str f)}))
        elapsed (- (System/currentTimeMillis) started)]
    (session/close! s :dogfood-end)
    (let [root (storage/open! state-root :sqlite)]
      (try
        (let [events (store/events root session-id)
              requests (filter #(= :repl/request (:event/type %)) events)
              results (filter #(= :repl/result (:event/type %)) events)
              sources (mapv :repl/source requests)
              statuses (frequencies (map #(get-in % [:repl/result :status]) results))
              text (str (:message outcome))
              named (into #{} (filter #(str/includes? text %)) ground-truth)]
          (merge outcome
                 {:profile profile :orientation orientation :index index
                  :session/id session-id
                  :elapsed/ms elapsed
                  :repl/attempts (count requests)
                  :repl/errors (get statuses :error 0)
                  :repl/sources sources
                  :used-list? (boolean (some #(re-find #"project/list" (str %)) sources))
                  :files-named named
                  :files-missed (into #{} (remove named) ground-truth)
                  :complete? (= ground-truth named)}))
        (finally (store/close-store! root))))))

(defn -main [& [state-root endpoint model repetitions]]
  (let [reps (parse-long (or repetitions "3"))
        arms [{:profile :agent/project-read :orientation :grounded
               :label "before: A1.1 surface + measured orientation"}
              {:profile :agent/project-survey :orientation :derived
               :label "after: A2 surface + derived orientation"}]
        observations
        (doall
         (for [index (range 1 (inc reps))
               ;; Alternated so run order does not load onto one arm.
               arm (if (odd? index) arms (reverse arms))]
           (let [o (run-arm! (assoc arm :state-root state-root :endpoint endpoint
                                :model model :index index))]
             (println (format "  %-14s %-9s %d  %-8s attempts %2d errors %2d list %-5s named %d/4 %7d ms"
                              (name (:profile arm)) (name (:orientation arm)) index
                              (name (:status o)) (:repl/attempts o) (:repl/errors o)
                              (str (:used-list? o)) (count (:files-named o))
                              (:elapsed/ms o)))
             (flush)
             o)))]
    (println "\n=== summary ===")
    (doseq [arm arms]
      (let [rs (filter #(and (= (:profile arm) (:profile %))
                             (= (:orientation arm) (:orientation %)))
                       observations)
            n (count rs)]
        (println (format "%s\n  finished %d/%d  used-list %d/%d  complete-answer %d/%d  attempts-mean %.1f  errors-mean %.1f"
                         (:label arm)
                         (count (filter #(= :finished (:status %)) rs)) n
                         (count (filter :used-list? rs)) n
                         (count (filter :complete? rs)) n
                         (double (/ (reduce + (map :repl/attempts rs)) n))
                         (double (/ (reduce + (map :repl/errors rs)) n))))))
    (spit "artifacts/a2-dogfood-runs.edn"
          (with-out-str (pprint/pprint (vec observations))))
    (println "\nwritten to artifacts/a2-dogfood-runs.edn")))

;; `clojure -M script/a2-dogfood.clj ARGS` loads this file; it does not call
;; -main by itself.
(apply -main *command-line-args*)
