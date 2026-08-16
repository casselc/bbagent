(ns bbagent.bb4t
  (:require [bb4t.context :as context]
            [bb4t.events :as events]
            [bb4t.runtime :as runtime]
            [bbagent.errors :as errors]))

(def profile :agent/project-read)
(def capabilities #{:data/json-read :data/json-write :project/read})

(def context-spec
  {:context-spec/version 1
   :profile profile
   :requested-capabilities capabilities
   :authorized-capabilities capabilities
   :resource-bindings {:project :project/root}
   :limits {:project/read-max-bytes 1048576}})

(defn create [project-root]
  (let [runtime (runtime/create {:resources {:project/root project-root}
                                 :event-limit 512})
        context (context/create runtime context-spec)]
    {:runtime runtime
     :context context
     :runtime/description (runtime/describe runtime)
     :context/description (context/describe context)}))

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
