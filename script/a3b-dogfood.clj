#!/usr/bin/env clojure -M
(ns a3b-dogfood
  "A3b dogfood: can a live model verify its own work?

  The task needs the whole loop and cannot be short-circuited. The fixture's
  check fails; nothing in the fixture states the fix, so the model has to read
  the check to learn what it wants. Nothing states the command either, so it
  has to find that too. And the fix has to be anchored, because project/edit
  refuses an unanchored write.

  Three arms, run in order against one fixture each:

    verify    inspect -> edit -> find the command -> run it -> react -> finish
    resume    the same, then process exit and resume, proving the recorded run
              is reconstructed rather than performed again
    unstable  the host mutates the project while the model's command is running,
              proving the model is told its result is not anchored

  Usage:
    clojure -M script/a3b-dogfood.clj STATE_ROOT ENDPOINT MODEL IMAGE_ARCHIVE [OUT]

  OUT defaults to artifacts/a3b-dogfood-runs.edn. Pass it when re-running this
  harness for a later milestone: writing over the previous milestone's record
  would replace evidence about a substrate that no longer exists with evidence
  about the current one, under the previous milestone's name."
  (:require [bbagent.agent :as agent]
            [bbagent.executor :as executor]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def task
  (str "This project has a check it verifies itself with, and the check is "
       "currently failing. Work out how this project is verified, fix what the "
       "check is complaining about, and then actually run the check to confirm "
       "your fix. Tell me what you changed and what the check said when you ran "
       "it."))

(defn- fixture! [label]
  (let [^Path root (Files/createTempDirectory (str "a3b-" label)
                                              (make-array FileAttribute 0))
        write (fn [rel content]
                (let [p (.resolve root ^String rel)]
                  (Files/createDirectories (.getParent p)
                                           (make-array FileAttribute 0))
                  (Files/writeString p ^String content
                                     (make-array java.nio.file.OpenOption 0))))]
    (write "README.md"
           (str "# Quarry\n\nA small batch pipeline.\n\n"
                "Everything under `script/` is run with babashka.\n"))
    ;; The defect. The check wants 9; nothing but the check says so.
    (write "src/quarry/lattice.clj"
           (str "(ns quarry.lattice)\n\n"
                ";; Spacing of the adjacency lattice used by the planner.\n"
                "(def spacing 7)\n\n"
                "(defn neighbours [n] (range n (+ n spacing)))\n"))
    (write "script/check.clj"
           (str ";; Verifies the lattice invariants.\n"
                "(def lattice (slurp \"src/quarry/lattice.clj\"))\n\n"
                "(def spacing\n"
                "  (some-> (re-find #\"\\(def spacing (\\d+)\\)\" lattice)\n"
                "          second parse-long))\n\n"
                "(when-not (= 9 spacing)\n"
                "  (println (str \"FAIL: lattice spacing must be 9 for the \"\n"
                "                \"planner, found \" spacing))\n"
                "  (System/exit 1))\n\n"
                "(println \"quarry check OK: spacing\" spacing)\n"))
    (str root)))

(defn- system-prompt [] (slurp (io/resource "bbagent/system.txt")))

(defn- provider! [endpoint model]
  (provider/openai-compatible
   {:endpoint endpoint :model model
    :api-key "lemonade-local" :allow-insecure-http true}))

(defn- session-facts
  "What the session's own durable record says happened."
  [state-root session-id]
  (let [root (storage/open! state-root :sqlite)]
    (try
      (let [events (store/events root session-id)
            requests (filter #(= :repl/request (:event/type %)) events)
            results (filter #(= :repl/result (:event/type %)) events)
            sources (mapv :repl/source requests)
            ;; Read from the operation receipts, not from what the form
            ;; evaluated to.  A model that writes (def r (project/run ...))
            ;; leaves a Var as the value and the receipt as the only place
            ;; the result itself survives.
            run-results (into []
                              (comp (mapcat :repl/operations)
                                    (filter #(= :project/run (:operation/id %)))
                                    (map :result))
                              results)]
        {:repl/attempts (count requests)
         :repl/errors (count (filter #(= :error (get-in % [:repl/result :status]))
                                     results))
         :repl/sources sources
         :used-run? (boolean (some #(str/includes? (str %) "project/run") sources))
         :used-edit? (boolean (some #(str/includes? (str %) "project/edit") sources))
         :run/results run-results})
      (finally (store/close-store! root)))))

(defn- replay-summary-of
  "What the resume recorded about reconstructing the session's own forms."
  [state-root session-id]
  (let [root (storage/open! state-root :sqlite)]
    (try
      (some->> (store/events root session-id)
               (filter #(= :session/resumed (:event/type %)))
               last
               :session/replay)
      (finally (store/close-store! root)))))

(defn- final-spacing [project-root]
  (some-> (re-find #"\(def spacing (\d+)\)"
                   (slurp (io/file project-root "src" "quarry" "lattice.clj")))
          second parse-long))

(defn- run-turn! [{:keys [state-root session-id project-root environment
                          endpoint model]}]
  (let [s (session/start! {:state-root state-root
                           :project-root project-root
                           :model-provider (provider! endpoint model)
                           :system-prompt (system-prompt)
                           :session-id session-id
                           :store-backend :sqlite
                           :profile :agent/project-execute
                           :executor {:environment environment}
                           :orientation :derived})
        outcome (try {:status :finished :message (agent/turn! s task)}
                     (catch clojure.lang.ExceptionInfo f
                       {:status :failed
                        :category (:bbagent/error (ex-data f))
                        :message (ex-message f)})
                     (catch Throwable f
                       {:status :failed :category :unclassified
                        :message (str f)}))]
    (session/close! s :dogfood-end)
    outcome))

(defn- verify-arm! [settings]
  (let [project-root (fixture! "verify")
        environment (executor/create {:image (:image settings) :project-root project-root})
        session-id "a3b-verify"
        outcome (run-turn! (assoc settings :session-id session-id
                                  :project-root project-root
                                  :environment environment))
        facts (session-facts (:state-root settings) session-id)
        runs (:run/results facts)]
    (merge outcome facts
           {:arm :verify
            :session/id session-id
            :project/root project-root
            :executions (executor/invocation-count environment)
            :final/spacing (final-spacing project-root)
            :fixed? (= 9 (final-spacing project-root))
            :ran-the-check? (:used-run? facts)
            :saw-a-passing-check?
            (boolean (some #(and (= :completed (:status %)) (= 0 (:exit %))
                                 (str/includes? (str (:stdout %))
                                                "quarry check OK"))
                           runs))
            ;; Only runs that actually executed can carry a coordinate. A
            ;; call refused before it reached the executor -- the first live
            ;; run spent one on :timeout instead of :timeout-ms -- leaves a
            ;; receipt with no result, and counting that as an unanchored run
            ;; said the product had done something it had not.
            :run/calls (count runs)
            :run/executed (count (filter :status runs))
            :run/refused-before-executor (count (remove :status runs))
            :every-executed-run-anchored?
            (let [executed (filter :status runs)]
              (and (seq executed)
                   (every? #(or (= :project-changed (:status %))
                                (string? (:project/input-coordinate %)))
                           executed)))})))

(defn- resume-arm! [settings]
  (let [project-root (fixture! "resume")
        environment (executor/create {:image (:image settings) :project-root project-root})
        session-id "a3b-resume"
        outcome (run-turn! (assoc settings :session-id session-id
                                  :project-root project-root
                                  :environment environment))
        after-turn (executor/invocation-count environment)
        facts (session-facts (:state-root settings) session-id)
        ;; A different process would be a stronger proof and is what the PTY
        ;; gates do; this is the same process with the Context thrown away,
        ;; which is what recovery actually rebuilds from.
        resumed (session/resume! {:state-root (:state-root settings)
                                  :session-id session-id
                                  :model-provider (provider! (:endpoint settings)
                                                             (:model settings))
                                  :system-prompt (system-prompt)
                                  :store-backend :sqlite
                                  :executor {:environment environment}})
        after-resume (executor/invocation-count environment)]
    (session/close! resumed :dogfood-end)
    (merge outcome facts
           {:arm :resume
            :session/id session-id
            :project/root project-root
            :executions/after-turn after-turn
            :executions/after-resume after-resume
            :no-second-execution? (= after-turn after-resume)
            :ran-the-check? (:used-run? facts)
            :fixed? (= 9 (final-spacing project-root))
            :replay (replay-summary-of (:state-root settings) session-id)})))

(defn- unstable-arm! [settings]
  (let [project-root (fixture! "unstable")
        environment (executor/create {:image (:image settings) :project-root project-root})
        session-id "a3b-unstable"
        ;; Moves the project continuously for the whole turn rather than at
        ;; a guessed moment, because a single timed write only lands inside a
        ;; run by luck.  The file is one the model has no reason to read, so
        ;; what it reacts to is the shape of its result and not the content.
        turn-over (promise)
        saboteur (future
                   (loop [n 0]
                     (when-not (realized? turn-over)
                       (spit (io/file project-root "NOTES.md")
                             (str "the host changed this mid-run " n))
                       (Thread/sleep 400)
                       (recur (inc n)))))
        outcome (run-turn! (assoc settings :session-id session-id
                                  :project-root project-root
                                  :environment environment))
        _ (deliver turn-over true)
        _ (deref saboteur 2000 nil)
        facts (session-facts (:state-root settings) session-id)
        runs (:run/results facts)]
    (merge outcome facts
           {:arm :unstable
            :session/id session-id
            :project/root project-root
            :executions (executor/invocation-count environment)
            :statuses (mapv :status runs)
            :saw-project-changed?
            (boolean (some #(= :project-changed (:status %)) runs))
            :unanchored-runs-carry-no-coordinate?
            (every? #(nil? (:project/input-coordinate %))
                    (filter #(= :project-changed (:status %)) runs))})))

(defn -main [& [state-root endpoint model image out]]
  (when-not (and state-root endpoint model image)
    (println "usage: STATE_ROOT ENDPOINT MODEL IMAGE_ARCHIVE [OUT]")
    (System/exit 2))
  (let [settings {:state-root state-root :endpoint endpoint
                  :model model :image image}
        observations
        (mapv (fn [arm!]
                (let [o (arm! settings)]
                  (println (format "  %-9s %-8s attempts %2d errors %2d run %-5s edit %-5s"
                                   (name (:arm o)) (name (:status o))
                                   (:repl/attempts o) (:repl/errors o)
                                   (str (:used-run? o)) (str (:used-edit? o))))
                  (flush)
                  o))
              [verify-arm! resume-arm! unstable-arm!])]
    (println "\n=== summary ===")
    (doseq [o observations]
      (println (format "%s: %s" (name (:arm o))
                       (pr-str (dissoc o :repl/sources :run/results :message)))))
    (let [out (or out "artifacts/a3b-dogfood-runs.edn")]
      (spit out (with-out-str (pprint/pprint (vec observations))))
      (println "\nwritten to" out))))

(apply -main *command-line-args*)
