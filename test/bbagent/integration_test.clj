(ns bbagent.integration-test
  (:require [bb4t.context :as context]
            [bb4t.runtime :as runtime]
            [bbagent.bb4t :as app-runtime]
            [bbagent.sqlite :as sqlite]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files Path]))

(defn- fixture-project []
  (let [^Path root (Files/createTempDirectory
                    "bbagent-project"
                    (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString (.resolve root "README.md") "bounded project"
                       (make-array java.nio.file.OpenOption 0))
    (str root)))

(deftest trusted-runtime-and-bounded-context-test
  (let [project-root (fixture-project)
        app (app-runtime/create project-root)
        description (:context/description app)]
    (is (= :agent/project-read
           (get-in description [:context/spec :profile])))
    (is (= app-runtime/capabilities
           (get-in description [:context/spec :requested-capabilities])))
    (is (= "bounded project"
           (get-in (app-runtime/evaluate app "(project/read \"README.md\")")
                   [:evaluation :value :value/data])))
    (testing "trusted bb4t host namespaces are not model-visible"
      (is (= :error
             (:status (app-runtime/evaluate app
                                            "(bb4t.runtime/create {})")))))))

(deftest project-read-requires-grant-test
  (let [project-root (fixture-project)
        host (runtime/create {:resources {:project/root project-root}})
        bounded (context/create host
                                {:context-spec/version 1
                                 :profile :agent/minimal
                                 :requested-capabilities #{}
                                 :authorized-capabilities #{}
                                 :resource-bindings {}
                                 :limits {}})]
    (is (thrown? Throwable
                  (context/evaluate bounded "(project/read \"README.md\")")))))

(deftest sqlite-reachability-does-not-widen-model-authority-test
  (let [project-root (fixture-project)
        ^Path database-root (Files/createTempDirectory
                             "bbagent-authority"
                             (make-array java.nio.file.attribute.FileAttribute 0))
        result (sqlite/authority-smoke! project-root
                                        (.resolve database-root "smoke.db"))
        surface (:context/surface result)]
    (is (= app-runtime/context-spec (:context/spec result)))
    (is (= app-runtime/capabilities
           (get-in result [:context/effective :context/grants])))
    (is (= 0 (:projected-class-count surface)))
    (is (= 0 (:supplied-import-count surface)))
    (is (= #{:ok} (set (vals (:positive-probes result)))))
    (is (= #{:error} (set (vals (:negative-probes result)))))
    (is (false? (:forbidden-database/created? result)))))
