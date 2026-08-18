(ns bbagent.sqlite-test
  (:require [bbagent.sqlite :as sqlite]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files Path]))

(defn- temporary-database []
  (let [^Path root (Files/createTempDirectory
                    "bbagent-sqlite"
                    (make-array java.nio.file.attribute.FileAttribute 0))]
    (.resolve root "smoke.db")))

(deftest file-backed-commit-reopen-and-rollback-test
  (let [^Path database (temporary-database)
        result (sqlite/database-smoke! database)]
    (is (= "1.3.1118" (:next.jdbc/version result)))
    (is (= "3.53.2.1" (:sqlite-jdbc/version result)))
    (is (= "3.53.2" (:sqlite/version result)))
    (is (= "3.53.2.1" (:jdbc-driver/version result)))
    (is (seq (:sqlite/compile-options result)))
    (is (= [{:id 1 :value "committed"}] (:committed/rows result)))
    (is (false? (:rollback/persisted? result)))
    (is (every? pos? (vals (:open-latency-ns result))))
    (is (pos? (:database/bytes result)))
    (testing "the destructive spike refuses to reuse an existing database"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"already exists"
                            (sqlite/database-smoke! database))))))
