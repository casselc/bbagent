(ns bbagent.core
  (:require [bbagent.agent :as agent]
            [bbagent.orientation :as orientation]
            [bbagent.provider :as provider]
            [bbagent.a3a-smoke :as a3a-smoke]
            [bbagent.a3b-smoke :as a3b-smoke]
            [bbagent.worker :as worker]
            [bbagent.s0b-smoke :as s0b-smoke]
            [bbagent.session :as session]
            [bbagent.sqlite :as sqlite]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [bbagent.tui.app :as tui]
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

(defn- true-value? [value]
  (= "true" (some-> value str str/lower-case)))

(defn- allow-insecure-http [options fallback]
  (let [environment (some-> (System/getenv "BBAGENT_ALLOW_INSECURE_HTTP")
                            not-empty)]
    (cond
      (contains? options :allow-insecure-http)
      (true-value? (:allow-insecure-http options))

      environment (true-value? environment)
      :else (boolean (:allow-insecure-http fallback)))))

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
    :allow-insecure-http (allow-insecure-http options fallback)
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

(defn- executor-options
  "The trusted host settings an executing profile runs under.

   Host configuration, never a model argument.  The tool bundle in
   particular: an execution capability that let its caller name a host
   directory to mount would be a way to read the host wearing an execution
   capability's clothes, whatever the machine boundary did afterwards."
  [options]
  {:tools (or (:tools options)
              (System/getenv "BBAGENT_EXECUTOR_TOOLS"))
   :allow-unapproved-version?
   (true-value? (or (:allow-unapproved-worker options)
                    (System/getenv "BBAGENT_EXECUTOR_ALLOW_UNAPPROVED")))})

(defn- tui-command
  "Opens or resumes a session through the ordinary application seams and
   hands the live AgentSession to the TUI.  The TUI never opens a store or
   constructs a Context itself."
  [options]
  (let [backend (:store options)
        session-id (first (:arguments options))
        agent-session
        (if session-id
          (let [root-store (storage/open! (state-root options) backend)
                start (try
                        (store/first-event root-store session-id :session/started)
                        (finally (store/close-store! root-store)))]
            (session/resume! {:state-root (state-root options)
                              :session-id session-id
                              :model-provider (model-provider
                                               options (:provider/config start))
                              :system-prompt (system-prompt options)
                              :store-backend backend
                              :orientation (:orientation options)
                              :executor (executor-options options)
                              :profile (some-> (:profile options) keyword)}))
          (session/start! {:state-root (state-root options)
                           :project-root (or (:project options) ".")
                           :model-provider (model-provider options nil)
                           :system-prompt (system-prompt options)
                           :store-backend backend
                           :orientation (:orientation options)
                           :executor (executor-options options)
                              :profile (some-> (:profile options) keyword)}))]
    (try
      (tui/start!
       {:agent-session agent-session
        :store-backend (storage/backend backend)
        :state-root (state-root options)})
      (finally (session/close! agent-session :operator-exit)))
    ;; The session is durably checkpointed and its store is closed by the
    ;; time this runs.  core.async, which charm uses for its command loop,
    ;; leaves pooled threads alive with a long keep-alive, so returning
    ;; normally makes the process linger for the better part of a minute
    ;; after the operator has already quit.  Exit deterministically instead;
    ;; nothing durable is outstanding at this point.
    (flush)
    (System/exit 0)))

(defn- run-command [options]
  (let [agent-session
        (session/start! {:state-root (state-root options)
                         :project-root (or (:project options) ".")
                         :model-provider (model-provider options nil)
                         :system-prompt (system-prompt options)
                         :store-backend (:store options)
                         :orientation (:orientation options)
                         :executor (executor-options options)
                              :profile (some-> (:profile options) keyword)})]
    (try (interactive! agent-session)
         (finally (session/close! agent-session :operator-exit)))))

(defn- resume-command [session-id options]
  (let [root-store (storage/open! (state-root options) (:store options))
        start (try
                (store/first-event root-store session-id :session/started)
                (finally (store/close-store! root-store)))
        fallback (:provider/config start)
        agent-session
        (session/resume! {:state-root (state-root options)
                          :session-id session-id
                          :model-provider (model-provider options fallback)
                          :system-prompt (system-prompt options)
                          :store-backend (:store options)
                          :orientation (:orientation options)
                          :executor (executor-options options)
                              :profile (some-> (:profile options) keyword)})]
    (try (interactive! agent-session)
         (finally (session/close! agent-session :operator-exit)))))

(defn- sessions-command [options]
  (let [root-store (storage/open! (state-root options) (:store options))]
    (try
      (doseq [session-id (store/list-sessions root-store)]
        (println session-id))
      (finally (store/close-store! root-store)))))

(defn- inspect-command [session-id options]
  (let [root-store (storage/open! (state-root options) (:store options))]
    (try
      (doseq [event (store/events root-store session-id)]
        (prn event))
      (finally (store/close-store! root-store)))))

(defn- sqlite-smoke-command [options]
  (let [unknown (seq (remove #{:arguments :database :project} (keys options)))]
    (when unknown
      (throw (ex-info "Unknown SQLite smoke options" {:options (vec unknown)})))
    (when (seq (:arguments options))
      (throw (ex-info "SQLite smoke does not accept positional arguments" {})))
    (when-not (:database options)
      (throw (ex-info "SQLite smoke requires --database PATH" {})))
    (when-not (:project options)
      (throw (ex-info "SQLite smoke requires --project PATH" {})))
    (prn (sqlite/smoke! {:database (:database options)
                          :project-root (:project options)}))))

(defn- s0b-smoke-command [options]
  (let [unknown (seq (remove #{:arguments :phase :project :session :state}
                             (keys options)))
        phase (:phase options)]
    (when unknown
      (throw (ex-info "Unknown S0b smoke options" {:options (vec unknown)})))
    (when (seq (:arguments options))
      (throw (ex-info "S0b smoke does not accept positional arguments" {})))
    (when-not (:state options)
      (throw (ex-info "S0b smoke requires --state PATH" {})))
    (when-not (:session options)
      (throw (ex-info "S0b smoke requires --session ID" {})))
    (case phase
      "create" (do
                 (when-not (:project options)
                   (throw (ex-info "S0b create requires --project PATH" {})))
                 (prn (s0b-smoke/create! {:state-root (:state options)
                                          :project-root (:project options)
                                          :session-id (:session options)})))
      "resume" (prn (s0b-smoke/resume! {:state-root (:state options)
                                         :session-id (:session options)}))
      "replay-create"
      (do
        (when-not (:project options)
          (throw (ex-info "S0b replay create requires --project PATH" {})))
        (prn (s0b-smoke/replay-create! {:state-root (:state options)
                                        :project-root (:project options)
                                        :session-id (:session options)})))
      "replay-resume"
      (prn (s0b-smoke/replay-resume! {:state-root (:state options)
                                      :session-id (:session options)}))
      "ambiguous-exit"
      (do
        (when-not (:project options)
          (throw (ex-info "S0b ambiguous exit requires --project PATH" {})))
        (s0b-smoke/ambiguous-exit! {:state-root (:state options)
                                    :project-root (:project options)
                                    :session-id (:session options)}))
      "ambiguous-check"
      (prn (s0b-smoke/ambiguous-check! {:state-root (:state options)
                                        :session-id (:session options)}))
      "transaction-exit"
      (s0b-smoke/transaction-exit! {:state-root (:state options)
                                    :session-id (:session options)})
      "transaction-check"
      (prn (s0b-smoke/transaction-check! {:state-root (:state options)
                                          :session-id (:session options)}))
      (throw (ex-info "S0b smoke requires a known --phase" {:phase phase})))))

(defn- a3a-smoke-command [options]
  (let [unknown (seq (remove #{:arguments :phase :project :sentinel :tools}
                             (keys options)))
        phase (:phase options)]
    (when unknown
      (throw (ex-info "Unknown A3a smoke options" {:options (vec unknown)})))
    (when (seq (:arguments options))
      (throw (ex-info "A3a smoke does not accept positional arguments" {})))
    (when-not (:project options)
      (throw (ex-info "A3a smoke requires --project PATH" {})))
    (case phase
      "probe" (do
                (when-not (:sentinel options)
                  (throw (ex-info "A3a probe requires --sentinel PATH" {})))
                (prn (a3a-smoke/probe! {:project-root (:project options)
                                        :outside-sentinel (:sentinel options)})))
      "timeout" (prn (a3a-smoke/timeout! {:project-root (:project options)}))
      "dogfood" (do
                  (when-not (:tools options)
                    (throw (ex-info "A3a dogfood requires --tools PATH" {})))
                  (prn (a3a-smoke/dogfood! {:project-root (:project options)
                                            :tools [(:tools options)]})))
      "describe" (prn (worker/describe))
      (throw (ex-info "A3a smoke requires a known --phase" {:phase phase})))))

(defn- emit-evidence!
  "Prints one evidence map the same way everywhere.

   `prn` abbreviates a map whose keys share a namespace, and whether it does
   depends on `*print-namespace-maps*`, which clojure.main binds to true and a
   native image leaves false.  The build gates grep this output, so the two
   have to agree; binding it here means a phase verified on the JVM is
   verifying the text the image will actually produce."
  [value]
  (binding [*print-namespace-maps* false]
    (prn value)))

(defn- a3b-smoke-command [options]
  (let [unknown (seq (remove #{:arguments :phase :project :sentinel :tools}
                             (keys options)))
        phase (:phase options)
        settings {:project-root (:project options)
                  :tools (:tools options)
                  :outside-sentinel (:sentinel options)}]
    (when unknown
      (throw (ex-info "Unknown A3b smoke options" {:options (vec unknown)})))
    (when (seq (:arguments options))
      (throw (ex-info "A3b smoke does not accept positional arguments" {})))
    (when-not (:project options)
      (throw (ex-info "A3b smoke requires --project PATH" {})))
    (when-not (:tools options)
      (throw (ex-info "A3b smoke requires --tools PATH" {})))
    (case phase
      "describe" (emit-evidence! (a3b-smoke/describe! settings))
      "version" (emit-evidence! (a3b-smoke/version! settings))
      "authority" (emit-evidence! (a3b-smoke/authority! settings))
      "probe" (do
                (when-not (:sentinel options)
                  (throw (ex-info "A3b probe requires --sentinel PATH" {})))
                (emit-evidence! (a3b-smoke/probe! settings)))
      "unstable" (emit-evidence! (a3b-smoke/unstable! settings))
      "replay" (emit-evidence! (a3b-smoke/replay! settings))
      "dogfood" (emit-evidence! (a3b-smoke/dogfood! settings))
      (throw (ex-info "A3b smoke requires a known --phase" {:phase phase})))))

(defn- usage []
  (str "bbagent tui [SESSION_ID] [--project PATH] [--store file|sqlite]\n"
       "bbagent run [--project PATH] [--store file|sqlite] [provider options]\n"
       "bbagent resume SESSION_ID [--store file|sqlite] [provider options]\n"
       "bbagent sessions [--state PATH] [--store file|sqlite]\n"
        "bbagent inspect SESSION_ID [--state PATH] [--store file|sqlite]\n"
        "bbagent s0a-sqlite-smoke --database PATH --project PATH\n"
        "bbagent s0b-native-smoke --phase PHASE --state PATH --session ID [--project PATH]\n"
        "bbagent a3a-worker-smoke --phase PHASE --project PATH [--tools PATH]\n"
        "bbagent a3b-execute-smoke --phase PHASE --project PATH --tools PATH\n"
       "--profile agent/project-read|agent/project-survey|\n"
       "          agent/project-develop|agent/project-execute\n"
       "  selects the capability surface and defaults to\n"
       "  agent/project-develop, which can change the project;\n"
       "  agent/project-survey is read-only and agent/project-read is the\n"
       "  frozen A0 surface. agent/project-execute adds project/run, which\n"
       "  runs the project's own commands in a disposable machine, and needs\n"
       "  --tools DIR (or BBAGENT_EXECUTOR_TOOLS) naming the trusted tool\n"
       "  bundle to mount. A resumed session keeps the profile it was\n"
       "  created with\n"
       "--orientation none|minimal|generated|grounded|derived selects the\n"
       "  model capability preamble; it adds no authority and defaults to\n"
       "  derived, which generates its claims from the granted surface\n"
       "  A resumed session keeps the orientation it started with unless\n"
       "  --orientation is given for that run\n"
       "provider options: --endpoint URL --model ID [--reasoning-effort VALUE]\n"
       "                  [--allow-insecure-http true]\n"
       "--store selects the durable backend and defaults to sqlite\n"
       "  new sessions use sqlite unless --store file is given\n"
       "  an existing session must be opened with the backend that stores\n"
       "  it; there is no migration between backends\n"))

(defn -main [& args]
  (let [[command & command-args] args
        options (parse-options command-args)
        positional (:arguments options)]
    (case command
      "run" (run-command options)
      "tui" (tui-command options)
      "resume" (if-let [session-id (first positional)]
                 (resume-command session-id options)
                 (throw (ex-info "resume requires a session ID" {})))
      "sessions" (sessions-command options)
      "inspect" (if-let [session-id (first positional)]
                   (inspect-command session-id options)
                   (throw (ex-info "inspect requires a session ID" {})))
      "s0a-sqlite-smoke" (sqlite-smoke-command options)
      "s0b-native-smoke" (s0b-smoke-command options)
      "a3a-worker-smoke" (a3a-smoke-command options)
      "a3b-execute-smoke" (a3b-smoke-command options)
      "describe" (prn {:application :bbagent
                         :scope :a1
                         :surface :persistent-sci})
      (print (usage)))))
