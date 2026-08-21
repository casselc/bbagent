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

(def bb4t-diagnostic-keys
  "Keys from a bb4t failure that may be shown to the model.

   An explicit allowlist rather than a filter, so a key added to a failure
   somewhere in the kernel is invisible until someone decides it is safe.
   Everything here is either a category, a bounded number, or a path relative
   to the project root; bb4t interpolates no host path into a failure message
   or into these values."
  [:bb4t/error :operation/id :capability/id :error/type
   :path :limit :bytes :budget
   :bbagent/conflict :conflict/expected :conflict/observed])

(defn- bb4t-data
  "The kernel's own failure data, which SCI wraps.

   SCI rethrows an evaluation failure with its own location data and keeps the
   original as a cause, so reading ex-data off the caught exception finds
   SCI's map rather than the kernel's. Walk the chain to the first frame that
   carries a bb4t marker; bounded, because a cause chain is not trusted to be
   short."
  [failure]
  (loop [current failure
         depth 0]
    (cond
      (or (nil? current) (> depth 8)) nil
      (contains? (ex-data current) :bb4t/error) (ex-data current)
      :else (recur (ex-cause current) (inc depth)))))

(defn normalize-bb4t [failure]
  (let [data (or (bb4t-data failure) (ex-data failure))
        category (if (= :unauthorized (:bb4t/error data))
                   :bb4t-authorization-denial
                   :bb4t-evaluation-failure)]
    (error category (.getMessage failure)
           {:bb4t/data (select-keys data bb4t-diagnostic-keys)
            :error/type (.getName (class failure))}
           failure)))
