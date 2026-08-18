(ns bbagent.sqlite
  (:require [bbagent.bb4t :as app-runtime]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.sql Connection DatabaseMetaData]
           [org.sqlite SQLiteJDBCLoader]))

(def ^:private result-options
  {:builder-fn rs/as-unqualified-lower-maps})

(defn- ensure! [condition message data]
  (when-not condition
    (throw (ex-info message data))))

(defn- database-path! [database]
  (let [^Path input (Paths/get (str database) (make-array String 0))
        ^Path absolute (.toAbsolutePath input)
        ^Path path (.normalize absolute)
        ^Path parent (.getParent path)]
    (ensure! parent "SQLite database path must have a parent" {:database (str path)})
    (ensure! (Files/isDirectory parent (make-array LinkOption 0))
             "SQLite database parent must exist"
             {:database (str path) :parent (str parent)})
    (ensure! (not (Files/exists path (make-array LinkOption 0)))
             "SQLite smoke database already exists"
             {:database (str path)})
    path))

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
  (let [^Path path (database-path! database)
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
     "java.sql.Statement" "java.sql.Statement"
     "java.sql.ResultSet" "java.sql.ResultSet"
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
          (pr-str (str forbidden)) " :session-id \"forbidden\"})")}))

(defn authority-smoke!
  "Proves the actual A0 Context remains unchanged despite trusted SQLite reachability."
  [project-root database]
  (let [^Path forbidden (forbidden-database-path database)
        _ (ensure! (not (Files/exists forbidden (make-array LinkOption 0)))
                   "Authority probe path already exists"
                   {:database (str forbidden)})
        app (app-runtime/create project-root)
        description (:context/description app)
        positives
        {:core (app-runtime/evaluate app "(+ 1 2)")
         :json-read (app-runtime/evaluate app
                                          "(data.json/read \"{\\\"ok\\\":true}\")")
         :json-write (app-runtime/evaluate app "(data.json/write {\"ok\" true})")
         :project-read (app-runtime/evaluate app "(project/read \"README.md\")")}
        negatives (into (sorted-map)
                        (map (fn [[probe source]]
                               [probe (app-runtime/evaluate app source)]))
                        (negative-sources forbidden))
        surface (:context/surface description)]
    (ensure! (= app-runtime/context-spec (:context/spec description))
             "SQLite changed the bounded ContextSpec"
             {:context/spec (:context/spec description)})
    (ensure! (= app-runtime/capabilities
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
