(ns bbagent.coordinates
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file LinkOption Path Paths]
           [java.security MessageDigest]
           [java.util UUID]))

(declare canonical-tree)

(defn- canonical-pr-str [value]
  (binding [*print-length* nil
            *print-level* nil
            *print-readably* true
            *print-dup* false]
    (pr-str value)))

(defn- encoded [value]
  (canonical-pr-str (canonical-tree value)))

(defn canonical-tree [value]
  (when (and (instance? clojure.lang.IMeta value) (seq (meta value)))
    (throw (ex-info "Coordinate data must not contain metadata"
                    {:bbagent/error :coordinate-invalid})))
  (cond
    (nil? value) [:nil]
    (boolean? value) [:boolean value]
    (string? value) [:string value]
    (char? value) [:character (str value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    (integer? value) [:integer (str (bigint value))]
    (instance? clojure.lang.TaggedLiteral value)
    [:tagged-literal (str (:tag value))
     (canonical-tree (:form value))]
    (record? value) (throw (ex-info "Records are not coordinate data"
                                    {:bbagent/error :coordinate-invalid}))
    (map? value) [:map (->> value
                            (map (fn [[k v]]
                                   [(canonical-tree k) (canonical-tree v)]))
                            (sort-by (comp canonical-pr-str first))
                            vec)]
    (vector? value) [:vector (mapv canonical-tree value)]
    (list? value) [:list (mapv canonical-tree value)]
    (set? value) [:set (->> value (sort-by encoded) (mapv canonical-tree))]
    :else (throw (ex-info "Unsupported coordinate data"
                         {:bbagent/error :coordinate-invalid
                          :value/type (some-> value class .getName)}))))

(defn canonical-string [value]
  (canonical-pr-str (canonical-tree value)))

(defn sha-256-bytes [^bytes value]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256") value)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes))))

(defn sha-256 [^String value]
  (sha-256-bytes (.getBytes value StandardCharsets/UTF_8)))

(defn digest [kind value]
  (when-not (qualified-keyword? kind)
    (throw (ex-info "Coordinate kind must be a qualified keyword"
                    {:bbagent/error :coordinate-invalid :kind kind})))
  (str "sha256:"
       (sha-256 (canonical-pr-str
                 [:bbagent.coordinate/v1 kind (canonical-tree value)]))))

(defn resource-coordinate [resource-name]
  (or (some-> (io/resource resource-name) slurp str/trim not-empty)
      "unknown"))

(defn- git-command [root & args]
  (try
    (let [command (into ["git" "-C" root] args)
          process (.start (ProcessBuilder. ^java.util.List command))
          stdout (future (slurp (.getInputStream process)))
          stderr (future (slurp (.getErrorStream process)))
          status (.waitFor process)]
      @stderr
      {:status status :output (str/trim @stdout)})
    (catch Throwable _ nil)))

(defn- successful-output [result]
  (when (and result (zero? (:status result)))
    (not-empty (:output result))))

(defn- safe-repository [repository]
  (when repository
    (try
      (let [uri (java.net.URI/create repository)]
        (cond
          (and (.getScheme uri) (.getHost uri))
          (str (java.net.URI. (str/lower-case (.getScheme uri)) nil
                              (.getHost uri) (.getPort uri)
                              (.getPath uri) nil nil))

          (nil? (.getScheme uri)) repository
          :else nil))
      (catch Throwable _ nil))))

(defn project-description [root]
  (let [^Path path (.toRealPath (Paths/get (str root) (make-array String 0))
                               (make-array LinkOption 0))
        canonical-root (str path)
        revision (successful-output
                  (git-command canonical-root "rev-parse" "HEAD"))
        status-result (when revision
                        (git-command canonical-root "status" "--porcelain"))]
    {:project/root canonical-root
     :project/revision revision
     :project/dirty? (when (and status-result (zero? (:status status-result)))
                       (boolean (seq (:output status-result))))
     :project/repository (safe-repository
                          (successful-output
                           (git-command canonical-root "config" "--get"
                                        "remote.origin.url")))}))

(defn new-session-id [] (str (UUID/randomUUID)))
(defn new-run-id [] (str (UUID/randomUUID)))

(defn session-envelope
  [{:keys [session-id run-id runtime-description context-description project
           provider endpoint model reasoning-effort allow-insecure-http
           system-prompt orientation]}]
  (let [manifest (:runtime/manifest runtime-description)
        spec (:context/spec context-description)
        effective (:context/effective context-description)]
    {:session/id session-id
     :run/id run-id
     :runtime {:bb4t/commit (:bb4t/commit manifest)
               :runtime/digest (:runtime/coordinate runtime-description)
               :catalog/digest (:catalog/coordinate runtime-description)}
     :agent {:bbagent/commit
             (resource-coordinate "META-INF/bbagent/commit")
             :profile :a0/single-agent}
     :model {:provider provider
             :endpoint endpoint
             :model model
             :reasoning-effort reasoning-effort
             :allow-insecure-http allow-insecure-http}
     :world project
     :context {:profile (:profile spec)
               :context-spec/digest
               (:context-spec/coordinate context-description)
               :effective/digest (:context/coordinate context-description)
               :requested-capabilities (:requested-capabilities spec)
               :authorized-capabilities (:authorized-capabilities spec)}
     :surface {:kind :persistent-sci :version 1}
     :prompt {:system/digest (digest :bbagent/system-prompt system-prompt)
              :orientation (or orientation :none)}
     :policy {:coordinate nil}}))
