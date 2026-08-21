(ns bbagent.bb4t
  (:require [bb4t.context :as context]
            [bb4t.events :as events]
            [bb4t.runtime :as runtime]
            [bbagent.errors :as errors]))

(def profiles
  "The context specs bbagent can ask bb4t for.

  :agent/project-read is the frozen A0/A1/A1.1 surface and is kept so a
  recorded coordinate can be reproduced exactly. :agent/project-survey is the
  A2 surface: the same authority plus directory listing, which the A1.1
  measurement showed the model correctly reporting as its missing capability.

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
             :project/write-max-bytes 1048576}}})

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
  ([project-root] (create project-root default-profile))
  ([project-root profile]
   (let [spec (context-spec profile)
         runtime (runtime/create {:resources {:project/root project-root}
                                  :event-limit 512})
         context (context/create runtime spec)]
     {:runtime runtime
      :context context
      :runtime/description (runtime/describe runtime)
      :context/description (context/describe context)})))

(defn evaluate [{:keys [context]} source]
  (try
    {:status :ok :evaluation (context/evaluate context source)}
    (catch Throwable failure
      {:status :error
       :error (ex-data (errors/normalize-bb4t failure))})))

(defn subscribe! [{:keys [runtime]} subscriber]
  (events/subscribe runtime subscriber))

(defn snapshot [{:keys [runtime]}]
  (events/snapshot runtime))
