(require '[bbagent.storage :as storage]
         '[bbagent.store :as store])
(import '[java.nio.file Files LinkOption Path]
        '[java.time Instant])

(defn elapsed [f]
  (let [started (System/nanoTime)
        value (f)]
    {:value value :ns (- (System/nanoTime) started)}))

(defn percentile [values fraction]
  (let [ordered (vec (sort values))
        index (min (dec (count ordered))
                   (dec (long (Math/ceil (* fraction (count ordered))))))]
    (nth ordered (max 0 index))))

(defn tree-bytes [root]
  (let [^Path path (.toAbsolutePath (java.nio.file.Paths/get
                                     (str root) (make-array String 0)))]
    (if-not (Files/exists path (make-array LinkOption 0))
      0
      (with-open [paths (Files/walk path (make-array java.nio.file.FileVisitOption 0))]
        (reduce + 0
                (map (fn [^Path entry]
                       (if (Files/isRegularFile entry (make-array LinkOption 0))
                         (Files/size entry)
                         0))
                     (iterator-seq (.iterator paths))))))))

(defn event [backend size index]
  (if (= index (- size 10))
    {:event/id (str (name backend) "-" size "-" index)
     :event/time "2026-08-18T00:00:00Z"
     :event/type :session/checkpoint
     :checkpoint/reason :measurement
     :session/messages [{:role :user :content "checkpoint"}]
     :repl/replay-forms []}
    {:event/id (str (name backend) "-" size "-" index)
     :event/time "2026-08-18T00:00:00Z"
     :event/type :user/message
     :request/id (str "request-" index)
     :action/id (str "action-" index)
     :message/content (str "event-" index)}))

(defn measure-case [backend size]
  (let [root (str (Files/createTempDirectory
                   (str "bbagent-s0b-" (name backend) "-" size "-")
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        session-id "measurement-session"
        opened (elapsed #(storage/open! root backend))
        root-store (:value opened)
        append-times
        (mapv (fn [index]
                (:ns (elapsed #(store/append-event! root-store session-id
                                                   (event backend size index)))))
              (range 1 (inc size)))
        object-content (apply str (repeat 70000 "x"))
        object-put (elapsed #(store/put-object! root-store session-id object-content))
        object-digest (:digest (:form (:value object-put)))
        object-get (elapsed #(store/get-object root-store session-id object-digest))
        full-read (elapsed #(count (store/events root-store session-id)))
        validate (elapsed #(store/validate-session! root-store session-id))
        checkpoint (elapsed #(store/latest-checkpoint root-store session-id))
        tail (elapsed #(count (store/events-after root-store session-id
                                                  (:event/id (:value checkpoint)))))
        live-bytes (tree-bytes root)]
    (store/close-store! root-store)
    (let [closed-bytes (tree-bytes root)
          reopened (elapsed #(storage/open! root backend))
          reopened-store (:value reopened)
          resume-window
          (elapsed #(do
                      (store/validate-session! reopened-store session-id)
                      (let [latest (store/latest-checkpoint reopened-store session-id)]
                        (count (store/events-after reopened-store session-id
                                                   (:event/id latest))))))]
      (store/close-store! reopened-store)
      {:events size
       :open-ns (:ns opened)
       :append {:median-ns (percentile append-times 0.5)
                :p95-ns (percentile append-times 0.95)
                :total-ns (reduce + append-times)}
       :full-read-ns (:ns full-read)
       :validate-ns (:ns validate)
       :latest-checkpoint-ns (:ns checkpoint)
       :tail-events (:value tail)
       :tail-read-ns (:ns tail)
       :reopen-ns (:ns reopened)
       :resume-window-ns (:ns resume-window)
       :cas-put-ns (:ns object-put)
       :cas-get-ns (:ns object-get)
       :storage-bytes {:live live-bytes :closed closed-bytes}})))

(defn measure-cumulative-checkpoints [backend]
  (let [root (str (Files/createTempDirectory
                   (str "bbagent-s0b-checkpoints-" (name backend) "-")
                   (make-array java.nio.file.attribute.FileAttribute 0)))
        root-store (storage/open! root backend)
        session-id "checkpoint-measurement"]
    (loop [index 1 messages []]
      (when (<= index 1000)
        (let [message {:role :user :content (str "message-" index)}
              accumulated (conj messages message)]
          (store/append-event! root-store session-id
                               {:event/id (str "message-" index)
                                :event/type :user/message
                                :message/content (:content message)})
          (when (zero? (mod index 100))
            (store/append-event! root-store session-id
                                 {:event/id (str "checkpoint-" index)
                                  :event/type :session/checkpoint
                                  :checkpoint/reason :measurement
                                  :session/messages accumulated
                                  :repl/replay-forms []}))
          (recur (inc index) accumulated))))
    (let [latest (elapsed #(store/latest-checkpoint root-store session-id))
          validation (elapsed #(store/validate-session! root-store session-id))
          live-bytes (tree-bytes root)]
      (store/close-store! root-store)
      {:messages 1000
       :checkpoint-every 100
       :events 1010
       :latest-checkpoint-ns (:ns latest)
       :validate-ns (:ns validation)
       :storage-bytes {:live live-bytes :closed (tree-bytes root)}})))

(let [sizes [100 1000 10000]
      result {:evidence/version 1
              :recorded-at (str (Instant/now))
              :method {:samples-per-size 1
                       :append-percentiles :within-session
                       :sizes sizes}
              :backends
              (into {}
                    (for [backend [:file :sqlite]]
                      [backend (mapv #(measure-case backend %) sizes)]))
              :cumulative-checkpoints
              (into {}
                    (for [backend [:file :sqlite]]
                      [backend (measure-cumulative-checkpoints backend)]))}]
  (prn result))
