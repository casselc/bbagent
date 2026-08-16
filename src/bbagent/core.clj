(ns bbagent.core
  (:require [bbagent.agent :as agent]
            [bbagent.journal :as journal]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:gen-class))

(defn- default-state-root []
  (str (io/file (System/getProperty "user.home") ".local" "state" "bbagent")))

(defn- parse-options [args]
  (loop [remaining args result {}]
    (if-let [arg (first remaining)]
      (if (str/starts-with? arg "--")
        (let [value (second remaining)]
          (when-not value
            (throw (ex-info (str "Missing value for " arg) {})))
          (recur (nnext remaining)
                 (assoc result (keyword (subs arg 2)) value)))
        (recur (next remaining) (update result :arguments (fnil conj []) arg)))
      result)))

(defn- system-prompt [options]
  (let [path (or (:system-prompt-file options)
                 (System/getenv "BBAGENT_SYSTEM_PROMPT_FILE"))]
    (if path
      (slurp path)
      (slurp (io/resource "bbagent/system.txt")))))

(defn- model-provider [options fallback]
  (provider/openai-compatible
   {:endpoint (or (:endpoint options)
                  (System/getenv "OPENAI_BASE_URL")
                  (:endpoint fallback))
    :model (or (:model options)
               (System/getenv "OPENAI_MODEL")
               (:model fallback))
    :reasoning-effort (or (:reasoning-effort options)
                          (:reasoning-effort fallback))
    :api-key (System/getenv "OPENAI_API_KEY")}))

(defn- state-root [options]
  (or (:state options) (System/getenv "BBAGENT_STATE_ROOT")
      (default-state-root)))

(defn- interactive! [agent-session]
  (println "session" (:session-id agent-session))
  (println "Enter a message, or /quit to checkpoint and exit.")
  (loop []
    (print "> ")
    (flush)
    (when-let [line (read-line)]
      (if (= "/quit" (str/trim line))
        nil
        (do
          (try
            (println (agent/turn! agent-session line))
            (catch Throwable failure
              (binding [*out* *err*]
                (println (pr-str {:error (:bbagent/error (ex-data failure))
                                  :message (.getMessage failure)})))))
          (recur))))))

(defn- run-command [options]
  (let [agent-session
        (session/start! {:state-root (state-root options)
                         :project-root (or (:project options) ".")
                         :model-provider (model-provider options nil)
                         :system-prompt (system-prompt options)})]
    (try (interactive! agent-session)
         (finally (session/close! agent-session :operator-exit)))))

(defn- resume-command [session-id options]
  (let [events (journal/read-events (state-root options) session-id)
        start (first (filter #(= :session/started (:event/type %)) events))
        fallback (:provider/config start)
        agent-session
        (session/resume! {:state-root (state-root options)
                          :session-id session-id
                          :model-provider (model-provider options fallback)
                          :system-prompt (system-prompt options)})]
    (try (interactive! agent-session)
         (finally (session/close! agent-session :operator-exit)))))

(defn- inspect-command [session-id options]
  (doseq [event (journal/read-events (state-root options) session-id)]
    (prn event)))

(defn- usage []
  (str "bbagent run [--project PATH] [provider options]\n"
       "bbagent resume SESSION_ID [provider options]\n"
       "bbagent sessions [--state PATH]\n"
       "bbagent inspect SESSION_ID [--state PATH]\n"))

(defn -main [& args]
  (let [[command & command-args] args
        options (parse-options command-args)
        positional (:arguments options)]
    (case command
      "run" (run-command options)
      "resume" (if-let [session-id (first positional)]
                 (resume-command session-id options)
                 (throw (ex-info "resume requires a session ID" {})))
      "sessions" (doseq [session-id (journal/list-sessions (state-root options))]
                   (println session-id))
      "inspect" (if-let [session-id (first positional)]
                  (inspect-command session-id options)
                  (throw (ex-info "inspect requires a session ID" {})))
      "describe" (prn {:application :bbagent
                        :scope :a0
                        :surface :persistent-sci})
      (print (usage)))))
