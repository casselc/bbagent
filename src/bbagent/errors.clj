(ns bbagent.errors)

(def categories
  #{:provider-failure
    :provider-malformed-response
    :agent-invalid-action
    :bb4t-authorization-denial
    :bb4t-evaluation-failure
    :journal-storage-failure
    :session-recovery-failure
    :user-cancellation})

(defn error
  ([category message] (error category message nil nil))
  ([category message data] (error category message data nil))
  ([category message data cause]
   (when-not (contains? categories category)
     (throw (IllegalArgumentException. (str "Unknown error category " category))))
   (ex-info message
            (merge {:bbagent/error category}
                   (when data {:error/data data}))
            cause)))

(defn normalize-bb4t [failure]
  (let [data (ex-data failure)
        category (if (= :unauthorized (:bb4t/error data))
                   :bb4t-authorization-denial
                   :bb4t-evaluation-failure)]
    (error category (.getMessage failure)
           {:bb4t/data (select-keys data [:bb4t/error :operation/id
                                          :capability/id :error/type])
            :error/type (.getName (class failure))}
           failure)))
