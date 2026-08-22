(ns bbagent.bb4t
  (:require [bb4t.context :as context]
            [bb4t.events :as events]
            [bb4t.runtime :as runtime]
            [bb4t.transcript :as transcript]
            [bbagent.errors :as errors]
            [bbagent.executor :as executor]))

(def profiles
  "The context specs bbagent can ask bb4t for.

  :agent/project-read is the frozen A0/A1/A1.1 surface and is kept so a
  recorded coordinate can be reproduced exactly. :agent/project-survey adds
  the observing capabilities A2 delivered -- listing, search and stat -- and
  stays read-only, so surveying a project remains a meaningful thing to
  authorize. :agent/project-develop is that surface plus project/edit, and is
  what a session defaults to.

  Every spec here is requested and authorized identically. bbagent asks for
  what it is willing to be held to; it does not hold authority in reserve."
  {:agent/project-read
   {:context-spec/version 1
    :profile :agent/project-read
    :requested-capabilities #{:data/json-read :data/json-write :project/read}
    :authorized-capabilities #{:data/json-read :data/json-write :project/read}
    :resource-bindings {:project :project/root}
    :limits {:project/read-max-bytes 1048576}}

   :agent/project-survey
   {:context-spec/version 1
    :profile :agent/project-survey
    :requested-capabilities #{:data/json-read :data/json-write
                              :project/read :project/list :project/search
                              :project/stat}
    :authorized-capabilities #{:data/json-read :data/json-write
                               :project/read :project/list :project/search
                               :project/stat}
    :resource-bindings {:project :project/root}
    :limits {:project/read-max-bytes 1048576
             :project/list-max-entries 4096
             :project/search-max-results 200
             :project/search-max-files 20000}}

   :agent/project-develop
   {:context-spec/version 1
    :profile :agent/project-develop
    :requested-capabilities #{:data/json-read :data/json-write
                              :project/read :project/list :project/search
                              :project/stat :project/edit}
    :authorized-capabilities #{:data/json-read :data/json-write
                               :project/read :project/list :project/search
                               :project/stat :project/edit}
    :resource-bindings {:project :project/root}
    :limits {:project/read-max-bytes 1048576
             :project/list-max-entries 4096
             :project/search-max-results 200
             :project/search-max-files 20000
             :project/write-max-bytes 1048576}}

   :agent/project-execute
   {:context-spec/version 1
    :profile :agent/project-execute
    :requested-capabilities #{:data/json-read :data/json-write
                              :project/read :project/list :project/search
                              :project/stat :project/edit :project/run}
    :authorized-capabilities #{:data/json-read :data/json-write
                               :project/read :project/list :project/search
                               :project/stat :project/edit :project/run}
    :resource-bindings {:project :project/root
                        :executor :execution/environment}
    :limits {:project/read-max-bytes 1048576
             :project/list-max-entries 4096
             :project/search-max-results 200
             :project/search-max-files 20000
             :project/write-max-bytes 1048576
             :project/run-max-timeout-ms 300000
             :project/run-max-stdout-bytes 1048576
             :project/run-max-stderr-bytes 1048576}}})

(def execution-profiles
  "Profiles that cannot be created without an authorized execution
   environment.  bb4t refuses one of these outright when the runtime has no
   environment bound; this is how bbagent knows to build one."
  #{:agent/project-execute})

(def default-profile
  "A2 asks whether the model can do real project work, which means changing
   the project. Sessions default to the writing profile; :agent/project-survey
   remains selectable and read-only, and :agent/project-read remains the frozen
   A0 surface."
  :agent/project-develop)

(defn context-spec
  "The spec for a profile.  An unknown profile fails closed rather than
   falling back to a surface the caller did not ask for."
  [profile]
  (or (get profiles profile)
      (throw (errors/error :agent-invalid-action
                           "Unknown context profile"
                           {:profile profile
                            :known (set (keys profiles))}))))

(defn capabilities
  "The authorized capability set for a profile."
  [profile]
  (:authorized-capabilities (context-spec profile)))

(defn create
  "The runtime and Context for one session.

   An executing profile builds its execution environment first and fails
   closed if it cannot.  That is deliberately not deferred to the first run:
   a session that believes it can verify its own work makes different
   decisions from one that knows it cannot, and it should find out before it
   has made any of them."
  ([project-root] (create project-root default-profile nil))
  ([project-root profile] (create project-root profile nil))
  ([project-root profile options]
   (let [spec (context-spec profile)
         ;; Trusted host code may hand over an environment it already built
         ;; rather than have one built here.  A resumed session reuses the
         ;; one its host is already holding, which is also what makes "the
         ;; resume did not run anything" measurable: the invocation counter
         ;; is on the environment, so it has to be the same environment.
         environment (when (contains? execution-profiles profile)
                       (or (:environment options)
                           (:environment (:executor options))
                           (executor/create (:executor options))))
         runtime (runtime/create
                  {:resources (cond-> {:project/root project-root}
                                environment
                                (assoc :execution/environment environment))
                   :event-limit 512})
         context (context/create runtime spec)]
     (cond-> {:runtime runtime
              :context context
              :runtime/description (runtime/describe runtime)
              :context/description (context/describe context)}
       environment (assoc :executor environment)))))

(defn- transcript-for
  "The transcript one evaluation runs under.

   nil is the ordinary path and records nothing, so an evaluation that is
   neither being journaled nor being recovered behaves exactly as it always
   did."
  [{:keys [transcript receipts]}]
  (case transcript
    :record (transcript/recorder)
    :replay (transcript/player receipts)
    :legacy (transcript/legacy)
    nil nil
    (throw (errors/error :agent-invalid-action "Unknown transcript mode"
                         {:transcript transcript}))))

(defn- with-transcript
  "Attaches what the transcript observed to a result.

   Recording is attached to a failed evaluation too: a form that changed the
   project and then failed has still changed it, and its receipt is the only
   record that it did."
  [result mode handle]
  (case mode
    :record (assoc result :operations (transcript/operations handle))
    :legacy (assoc result :observations (transcript/observations handle))
    result))

(defn evaluate
  "Evaluates source in the session's bounded Context.

   options selects what happens at the semantic operation boundary:

     nil                      evaluate; record nothing
     {:transcript :record}    invoke operations and record their receipts,
                              returned as :operations
     {:transcript :replay
      :receipts [...]}        reproduce those receipts instead of invoking
                              anything, failing closed on any divergence
     {:transcript :legacy}    for source recorded before receipts existed:
                              re-observe, refuse to actuate, and report what
                              was re-observed as :observations

   A replay that diverged returns :transcript/error naming the divergence.
   That is not the same thing as a form that failed, and recovery must not
   read it as one: a status can match while the reconstruction is wrong."
  ([runtime source] (evaluate runtime source nil))
  ([{:keys [context]} source options]
   (let [mode (:transcript options)
         handle (transcript-for options)]
     (try
       (-> {:status :ok :evaluation (context/evaluate context source handle)}
           (with-transcript mode handle))
       (catch Throwable failure
         ;; The message is the diagnostic. Dropping it made a refusal say only
         ;; that something failed, so the model had to guess at the shape it got
         ;; wrong instead of being told. bb4t's failure messages are authored
         ;; strings and interpolate no host path.
         (let [normalized (errors/normalize-bb4t failure)]
           (cond-> (-> {:status :error
                        :error (assoc (ex-data normalized)
                                      :error/message (ex-message normalized))}
                       (with-transcript mode handle))
             (errors/transcript-error failure)
             (assoc :transcript/error (errors/transcript-error failure)
                    :transcript/message (ex-message normalized)))))))))

(defn subscribe! [{:keys [runtime]} subscriber]
  (events/subscribe runtime subscriber))

(defn snapshot [{:keys [runtime]}]
  (events/snapshot runtime))
