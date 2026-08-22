(ns bbagent.sqlite
  (:require [bbagent.bb4t :as app-runtime]
            [bbagent.coordinates :as coordinates]
            [bbagent.errors :as errors]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.sql Connection DatabaseMetaData]
           [org.sqlite SQLiteJDBCLoader]))

(def ^:private result-options
  {:builder-fn rs/as-unqualified-lower-maps})

(def ^:private sqlite-sidecar-sha256
  "f374da845a36d0a663521457f8e454413325e3b8247a15c2677426f4b15cf6ac")

(def ^:private authority-negative-count 58)

(defn- ensure! [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn- ensure-store! [condition message data]
  (when-not condition
    (throw (errors/error :journal-storage-failure message data))))

(defn verify-native-sqlite-sidecar!
  "In a native image, verifies and selects the executable-adjacent SQLite JNI
   sidecar before sqlite-jdbc is loaded. On the JVM this is a no-op."
  []
  (when (= "runtime" (System/getProperty "org.graalvm.nativeimage.imagecode"))
    (try
      (let [^Class process-properties
            (Class/forName "org.graalvm.nativeimage.ProcessProperties")
            executable-name
            (-> (.getMethod process-properties "getExecutableName"
                            (make-array Class 0))
                (.invoke nil (object-array 0)))
            _ (ensure-store! (and (string? executable-name)
                                  (not-empty executable-name))
                             "Could not resolve native executable for SQLite sidecar"
                             {})
            ^Path executable
            (.toRealPath (Paths/get ^String executable-name
                                    (make-array String 0))
                         (make-array LinkOption 0))
            ^Path sidecar (.resolve (.getParent executable) "libsqlitejdbc.so")
            _ (ensure-store!
               (and (Files/isRegularFile sidecar (make-array LinkOption 0))
                    (Files/isExecutable sidecar))
               "SQLite native sidecar is not a regular executable file"
               {:sidecar/path (str sidecar)})
            actual (coordinates/sha-256-bytes (Files/readAllBytes sidecar))]
        (ensure-store! (= sqlite-sidecar-sha256 actual)
                       "SQLite native sidecar SHA-256 mismatch"
                       {:sidecar/path (str sidecar)
                        :sha256/expected sqlite-sidecar-sha256
                        :sha256/actual actual})
        (System/setProperty "org.sqlite.lib.path" (str (.getParent sidecar)))
        (System/setProperty "org.sqlite.lib.name" (str (.getFileName sidecar)))
        nil)
      (catch clojure.lang.ExceptionInfo failure
        (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
          (throw failure)
          (throw (errors/error :journal-storage-failure
                               "Could not verify native SQLite sidecar" {}
                               failure))))
      (catch Throwable failure
        (throw (errors/error :journal-storage-failure
                             "Could not verify native SQLite sidecar" {}
                             failure)))))
  nil)

(defn- database-path! [database]
  (try
    (let [^Path input (Paths/get (str database) (make-array String 0))
          ^Path absolute (.toAbsolutePath input)
          ^Path path (.normalize absolute)
          ^Path parent (.getParent path)]
      (ensure-store! parent "SQLite database path must have a parent"
                     {:database (str path)})
      (ensure-store! (Files/isDirectory parent (make-array LinkOption 0))
                     "SQLite database parent must exist"
                     {:database (str path) :parent (str parent)})
      (ensure-store! (not (Files/exists path (make-array LinkOption 0)))
                     "SQLite smoke database already exists"
                     {:database (str path)})
      path)
    (catch clojure.lang.ExceptionInfo failure
      (if (= :journal-storage-failure (:bbagent/error (ex-data failure)))
        (throw failure)
        (throw (errors/error :journal-storage-failure
                             "Invalid SQLite database path"
                             {:database (str database)} failure))))
    (catch Throwable failure
      (throw (errors/error :journal-storage-failure
                           "Invalid SQLite database path"
                           {:database (str database)} failure)))))

(defn- open-connection [datasource]
  (let [started (System/nanoTime)
        connection (jdbc/get-connection datasource)]
    {:connection connection
     :latency-ns (- (System/nanoTime) started)}))

(defn- sqlite-version [connection]
  (:sqlite_version
   (jdbc/execute-one! connection
                      ["select sqlite_version() as sqlite_version"]
                      result-options)))

(defn- compile-options [connection]
  (->> (jdbc/execute! connection ["pragma compile_options"] result-options)
       (mapv :compile_options)
       sort
       vec))

(defn- rows [connection]
  (jdbc/execute! connection
                 ["select id, value from s0a_smoke order by id"]
                 result-options))

(defn database-smoke!
  "Exercises file-backed SQLite from trusted host code and returns inert evidence."
  [database]
  (let [_ (verify-native-sqlite-sidecar!)
        ^Path path (database-path! database)
        _ (Class/forName "org.sqlite.JDBC")
        datasource (jdbc/get-datasource (str "jdbc:sqlite:" path))
        first-open (open-connection datasource)
        ^Connection first-connection (:connection first-open)
        first-evidence
        (try
          (let [^DatabaseMetaData metadata (.getMetaData first-connection)
                version (sqlite-version first-connection)
                options (compile-options first-connection)]
            (jdbc/execute-one!
             first-connection
             ["create table s0a_smoke (id integer primary key, value text not null)"])
            (jdbc/with-transaction [transaction first-connection]
              (jdbc/execute-one!
               transaction
               ["insert into s0a_smoke (id, value) values (?, ?)"
                1 "committed"]))
            {:jdbc-driver/version (.getDriverVersion metadata)
             :sqlite/version version
             :sqlite/compile-options options})
          (finally
            (.close first-connection)))
        warm-open (open-connection datasource)
        ^Connection warm-connection (:connection warm-open)
        second-evidence
        (try
          (let [persisted (rows warm-connection)
                rollback-visible
                (jdbc/with-transaction [transaction warm-connection
                                        {:rollback-only true}]
                  (jdbc/execute-one!
                   transaction
                   ["insert into s0a_smoke (id, value) values (?, ?)"
                    2 "rolled-back"])
                  (rows transaction))]
            (ensure! (= [{:id 1 :value "committed"}] persisted)
                     "Committed SQLite row was not recovered"
                     {:rows persisted})
            (ensure! (= [{:id 1 :value "committed"}
                         {:id 2 :value "rolled-back"}]
                        rollback-visible)
                     "Rollback mutation was not visible inside its transaction"
                     {:rows rollback-visible})
            {:committed-rows persisted
             :rollback-visible-rows rollback-visible})
          (finally
            (.close warm-connection)))
        verification-open (open-connection datasource)
        ^Connection verification-connection (:connection verification-open)
        final-rows
        (try
          (rows verification-connection)
          (finally
            (.close verification-connection)))]
    (ensure! (= (:committed-rows second-evidence) final-rows)
             "Rolled-back SQLite row persisted"
             {:committed (:committed-rows second-evidence) :final final-rows})
    {:database/path (str path)
     :database/bytes (Files/size path)
     :sqlite-jdbc/version (SQLiteJDBCLoader/getVersion)
     :jdbc-driver/version (:jdbc-driver/version first-evidence)
     :sqlite/version (:sqlite/version first-evidence)
     :sqlite/compile-options (:sqlite/compile-options first-evidence)
     :open-latency-ns {:first (:latency-ns first-open)
                       :warm (:latency-ns warm-open)
                       :verification (:latency-ns verification-open)}
     :committed/rows final-rows
     :rollback/visible-before-rollback?
     (= 2 (count (:rollback-visible-rows second-evidence)))
     :rollback/persisted? false}))

(defn- forbidden-database-path [database]
  (let [^Path input (Paths/get (str database) (make-array String 0))
        ^Path absolute (.toAbsolutePath input)
        ^Path database-path (.normalize absolute)]
    (.resolveSibling database-path "s0a-forbidden.db")))

(defn- negative-sources [^Path forbidden]
  (let [jdbc-url (str "jdbc:sqlite:" forbidden)]
    {"java.sql.DriverManager" "java.sql.DriverManager"
     "java.sql.Connection" "java.sql.Connection"
     "java.sql.Date" "java.sql.Date"
     "java.sql.Statement" "java.sql.Statement"
     "java.sql.ResultSet" "java.sql.ResultSet"
     "java.sql.Timestamp" "java.sql.Timestamp"
     "java.sql.DriverManager/getConnection"
     (str "(java.sql.DriverManager/getConnection " (pr-str jdbc-url) ")")
     "org.sqlite.JDBC" "org.sqlite.JDBC"
     "org.sqlite.SQLiteConnection" "org.sqlite.SQLiteConnection"
     "next.jdbc/require" "(require '[next.jdbc :as jdbc])"
     "next.jdbc/get-datasource"
     (str "(next.jdbc/get-datasource " (pr-str jdbc-url) ")")
     "bbagent.sqlite/require" "(require '[bbagent.sqlite :as sqlite])"
     "bbagent.sqlite/database-smoke!"
     (str "(bbagent.sqlite/database-smoke! " (pr-str (str forbidden)) ")")
     "bbagent.store/require" "(require '[bbagent.store :as store])"
     "bbagent.journal/require" "(require '[bbagent.journal :as journal])"
     "bbagent.journal/file-store"
     (str "(bbagent.journal/file-store " (pr-str (str forbidden)) ")")
     "bbagent.storage/require" "(require '[bbagent.storage :as storage])"
     "bbagent.sqlite-store/require"
     "(require '[bbagent.sqlite-store :as sqlite-store])"
     "bbagent.storage/open!"
     (str "(bbagent.storage/open! " (pr-str (str forbidden)) " :sqlite)")
     "bbagent.sqlite-store/sqlite-store"
     (str "(bbagent.sqlite-store/sqlite-store " (pr-str (str forbidden)) ")")
     "bbagent.s0b-smoke/require" "(require '[bbagent.s0b-smoke :as smoke])"
     "bbagent.s0b-smoke/transaction-check!"
     (str "(bbagent.s0b-smoke/transaction-check! {:state-root "
          (pr-str (str forbidden)) " :session-id \"forbidden\"})")

     ;; A1: the image now compiles a TUI.  The model Context must not
     ;; inherit terminal, JLine, charm, or TUI-adapter authority, and must
     ;; not obtain a terminal handle, a worker, or a UI callback.
     "org.jline.terminal.Terminal" "org.jline.terminal.Terminal"
     "org.jline.terminal.TerminalBuilder" "org.jline.terminal.TerminalBuilder"
     "org.jline.terminal.TerminalBuilder/terminal"
     "(org.jline.terminal.TerminalBuilder/terminal)"
     "org.jline.utils.InfoCmp$Capability" "org.jline.utils.InfoCmp$Capability"
     "org.jline.keymap.KeyMap" "org.jline.keymap.KeyMap"
     "org.jline.reader.LineReader" "org.jline.reader.LineReader"
     "charm.program/require" "(require '[charm.program :as program])"
     "charm.terminal/require" "(require '[charm.terminal :as terminal])"
     "charm.terminal/create-terminal" "(charm.terminal/create-terminal)"
     "bbagent.tui.app/require" "(require '[bbagent.tui.app :as tui])"
     "bbagent.tui.command/require"
     "(require '[bbagent.tui.command :as tui-command])"
     "bbagent.tui.command/start-worker!"
     "(bbagent.tui.command/start-worker! {})"
     "bbagent.tui.state/require" "(require '[bbagent.tui.state :as tui-state])"

     ;; A3a: the image now drives a virtual machine manager to run
     ;; project-owned code.  That is trusted host reachability and nothing
     ;; else.  The model gains no execution operation, cannot name the
     ;; implementation, and cannot reach the process primitive underneath
     ;; it or the general-purpose JVM process API beside it.
     "bbagent.worker/require" "(require '[bbagent.worker :as worker])"
     "bbagent.worker/execute!"
     "(bbagent.worker/execute! {:project-root \".\" :argv [\"true\"]})"
     "bbagent.worker/describe" "(bbagent.worker/describe)"
     "bbagent.process/require" "(require '[bbagent.process :as process])"
     "bbagent.process/execute!"
     "(bbagent.process/execute! {:argv [\"true\"] :timeout-ms 1000})"
     "bbagent.snapshot/require" "(require '[bbagent.snapshot :as snapshot])"
     "bbagent.snapshot/manifest" "(bbagent.snapshot/manifest \".\")"
     "java.lang.ProcessBuilder" "java.lang.ProcessBuilder"
     "java.lang.ProcessBuilder/start"
     "(.start (java.lang.ProcessBuilder. [\"true\"]))"
     "java.lang.Runtime" "java.lang.Runtime"
     "java.lang.Runtime/getRuntime" "(java.lang.Runtime/getRuntime)"
     "java.lang.ProcessHandle" "java.lang.ProcessHandle"
     "clojure.java.shell/require" "(require '[clojure.java.shell :as shell])"
     "project/run" "(project/run {:argv [\"true\"]})"
     "process/run" "(process/run {:argv [\"true\"]})"
     "smolvm/run" "(smolvm/run {:argv [\"true\"]})"

     ;; A3b: one profile can now run the project's own commands.  This one
     ;; cannot, and the corpus is what says so.  The seam that carries
     ;; execution is trusted host code on both sides of the bb4t boundary,
     ;; so neither side is nameable from a bounded Context, and neither is
     ;; the host directory the tool bundle comes from.
     "bb4t.execution/require" "(require '[bb4t.execution :as execution])"
     "bb4t.execution/describe" "(bb4t.execution/describe nil)"
     "bbagent.executor/require" "(require '[bbagent.executor :as executor])"
     "bbagent.executor/create" "(bbagent.executor/create {:tools \"/\"})"
     "bbagent.executor/approved-versions" "bbagent.executor/approved-versions"
     "bbagent.bb4t/require" "(require '[bbagent.bb4t :as app-runtime])"
     "bbagent.bb4t/create" "(bbagent.bb4t/create \".\" :agent/project-execute)"}))

(defn authority-smoke!
  "Proves the actual A0 Context remains unchanged despite trusted SQLite reachability."
  [project-root database]
  (let [^Path forbidden (forbidden-database-path database)
        _ (ensure! (not (Files/exists forbidden (make-array LinkOption 0)))
                   "Authority probe path already exists"
                   {:database (str forbidden)})
        ;; Pinned explicitly: the probe's whole claim is that trusted SQLite
        ;; reachability does not widen this exact surface, so the surface it
        ;; compares against must be named rather than inherited.
        profile app-runtime/default-profile
        app (app-runtime/create project-root profile)
        description (:context/description app)
        positives
        {:core (app-runtime/evaluate app "(+ 1 2)")
         :json-read (app-runtime/evaluate app
                                          "(data.json/read \"{\\\"ok\\\":true}\")")
         :json-write (app-runtime/evaluate app "(data.json/write {\"ok\" true})")
         :project-read (app-runtime/evaluate app "(project/read \"README.md\")")
         :project-list (app-runtime/evaluate app "(project/list \".\")")
         ;; A2 capabilities and the vocabulary that composes over them, so the
         ;; native image proves the whole path rather than the two operations
         ;; A0 shipped with.
         :project-search (app-runtime/evaluate app
                                               "(project/search \"fixture\")")
         ;; The write path, proved in the image: an edit anchored to a stat
         ;; digest applies, and the same stale base is then refused.
         :project-stat (app-runtime/evaluate app "(project/stat \"README.md\")")
         :project-edit-anchored
         (app-runtime/evaluate
          app
          (str "(let [c (project/stat \"README.md\")] "
               "(project/edit {:path \"README.md\" :base {:digest (:digest c)} "
               ":content (str (project/read \"README.md\") \"edited\n\")}))"))
         :project-edit-conflict-refused
         (let [stale (app-runtime/evaluate
                      app
                      (str "(project/edit {:path \"README.md\" "
                           ":base {:digest \"sha256:00\"} :content \"clobber\"})"))]
           ;; A refusal is the pass here, so invert it into the positive map.
           {:status (if (= :error (:status stale)) :ok :error)})
         :composition (app-runtime/evaluate
                       app
                       (str "(do (defn names [es] (mapv :name es)) "
                            "(count (str/join \",\" (names (project/list \".\")))))"))}
        negatives (into (sorted-map)
                         (map (fn [[probe source]]
                                [probe (app-runtime/evaluate app source)]))
                         (negative-sources forbidden))
        surface (:context/surface description)]
    (ensure! (= authority-negative-count (count negatives))
             "SQLite authority probe count changed"
             {:expected authority-negative-count :actual (count negatives)})
    (ensure! (= (app-runtime/context-spec profile) (:context/spec description))
             "SQLite changed the bounded ContextSpec"
             {:context/spec (:context/spec description)})
    (ensure! (= (app-runtime/capabilities profile)
                (get-in description [:context/effective :context/grants]))
             "SQLite changed effective Context grants"
             {:context/effective (:context/effective description)})
    (ensure! (= 0 (:projected-class-count surface))
             "SQLite projected Java classes into bounded SCI"
             {:context/surface surface})
    (ensure! (= 0 (:supplied-import-count surface))
             "SQLite supplied imports to bounded SCI"
             {:context/surface surface})
    (ensure! (= #{:ok} (set (map :status (vals positives))))
             "Existing bounded capabilities did not remain available"
             {:positive-probes positives})
    (ensure! (= #{:error} (set (map :status (vals negatives))))
             "SQLite or JDBC became available to bounded SCI"
             {:negative-probes negatives})
    (ensure! (= #{:bb4t-evaluation-failure}
                (set (map #(get-in % [:error :bbagent/error])
                          (vals negatives))))
             "A SQLite authority probe failed for an unexpected reason"
             {:negative-probes negatives})
    (ensure! (not (Files/exists forbidden (make-array LinkOption 0)))
             "A bounded SCI authority probe created a database"
             {:database (str forbidden)})
    {:context/spec (:context/spec description)
     :context/effective (:context/effective description)
     :context/surface surface
     :positive-probes
     (into (sorted-map)
           (map (fn [[probe result]] [probe (:status result)]))
           positives)
     :negative-probe/count (count negatives)
     :negative-probes
     (into (sorted-map)
           (map (fn [[probe result]]
                  [probe {:status (:status result)
                          :error/category (get-in result [:error :bbagent/error])}]))
           negatives)
     :forbidden-database/created? false}))

(defn smoke! [{:keys [database project-root]}]
  {:sqlite (database-smoke! database)
   :authority (authority-smoke! project-root database)})
