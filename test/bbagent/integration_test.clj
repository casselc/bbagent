(ns bbagent.integration-test
  (:require [bb4t.context :as context]
            [bb4t.runtime :as runtime]
            [bbagent.bb4t :as app-runtime]
            [bbagent.sqlite :as sqlite]
            [clojure.string :as str]
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
    (is (= :agent/project-survey
           (get-in description [:context/spec :profile]))
        "A2 sessions get the surveying surface by default")
    (is (= (app-runtime/capabilities app-runtime/default-profile)
           (get-in description [:context/spec :requested-capabilities])))
    (is (= #{:data/json-read :data/json-write :project/read :project/list}
           (get-in description [:context/effective :context/grants])))
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
    (is (= (app-runtime/context-spec app-runtime/default-profile)
           (:context/spec result)))
    (is (= (app-runtime/capabilities app-runtime/default-profile)
           (get-in result [:context/effective :context/grants])))
    (is (= 0 (:projected-class-count surface)))
    (is (= 0 (:supplied-import-count surface)))
    (is (= #{:ok} (set (vals (:positive-probes result)))))
    (is (= #{:error}
           (set (map :status (vals (:negative-probes result))))))
    (is (= #{:bb4t-evaluation-failure}
            (set (map :error/category (vals (:negative-probes result))))))
    (is (= 35 (:negative-probe/count result)))
    (is (every? #(contains? (:negative-probes result) %)
                ["java.sql.Date" "java.sql.Timestamp"
                 "bbagent.journal/require" "bbagent.journal/file-store"]))
    (testing "compiling a TUI into the image does not widen model authority"
      (is (every? #(contains? (:negative-probes result) %)
                  ["org.jline.terminal.Terminal"
                   "org.jline.terminal.TerminalBuilder/terminal"
                   "org.jline.keymap.KeyMap"
                   "charm.program/require"
                   "charm.terminal/create-terminal"
                   "bbagent.tui.app/require"
                   "bbagent.tui.command/start-worker!"])))
    (is (false? (:forbidden-database/created? result)))))

(deftest frozen-a0-profile-is-unchanged-test
  (testing "the recorded A0/A1/A1.1 surface is reproducible exactly"
    ;; A2 adds a capability by adding a profile, not by widening the one every
    ;; earlier coordinate was measured against.
    (let [app (app-runtime/create (fixture-project) :agent/project-read)
          description (:context/description app)]
      (is (= #{:data/json-read :data/json-write :project/read}
             (get-in description [:context/effective :context/grants])))
      (is (= 0 (get-in description [:context/surface :projected-class-count])))
      (is (= 0 (get-in description [:context/surface :supplied-import-count])))
      (is (= ['data.json/read 'data.json/write 'project/read]
             (mapv :sci/var (get-in description [:context/surface :projections]))))
      (testing "listing is not reachable from the frozen profile"
        (is (= :error
               (:status (app-runtime/evaluate app "(project/list \".\")"))))))))

;; ---------------------------------------------------------------------------
;; project/list authority boundary
;; ---------------------------------------------------------------------------

(defn- survey-project
  "A fixture with a nested directory, an empty directory, a symbolic link
   that escapes the root, and a link to an inside directory."
  []
  (let [^Path root (Files/createTempDirectory
                    "bbagent-survey"
                    (make-array java.nio.file.attribute.FileAttribute 0))
        write (fn [rel content]
                (let [p (.resolve root ^String rel)]
                  (Files/createDirectories
                   (.getParent p)
                   (make-array java.nio.file.attribute.FileAttribute 0))
                  (Files/writeString p ^String content
                                     (make-array java.nio.file.OpenOption 0))))]
    (write "README.md" "bounded project")
    (write "deps.edn" "{}")
    (write "src/example/core.clj" "(ns example.core)")
    (Files/createDirectories
     (.resolve root "empty")
     (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createSymbolicLink (.resolve root "escape") (.resolve root "..")
                              (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createSymbolicLink (.resolve root "inside") (.resolve root "src")
                              (make-array java.nio.file.attribute.FileAttribute 0))
    (str root)))

(defn- listing [app source]
  (get-in (app-runtime/evaluate app source) [:evaluation :value :value/data]))

(defn- denied? [app source]
  (= :error (:status (app-runtime/evaluate app source))))

(deftest project-list-returns-a-sorted-inert-listing-test
  (let [app (app-runtime/create (survey-project))]
    (testing "the root is listable, which is the operation's primary use"
      (is (= [{:name "README.md" :kind :file :bytes 15}
              {:name "deps.edn" :kind :file :bytes 2}
              {:name "empty" :kind :directory}
              {:name "escape" :kind :symlink}
              {:name "inside" :kind :symlink}
              {:name "src" :kind :directory}]
             (listing app "(project/list \".\")"))
          "sorted by name, so a listing is a value and not an iteration artefact"))
    (testing "a nested directory lists only its own entries"
      (is (= [{:name "core.clj" :kind :file :bytes 17}]
             (listing app "(project/list \"src/example\")"))))
    (testing "listing does not recurse"
      (is (= [{:name "example" :kind :directory}]
             (listing app "(project/list \"src\")"))))
    (testing "an empty directory lists as empty rather than failing"
      (is (= [] (listing app "(project/list \"empty\")"))))))

(deftest project-list-cannot-leave-the-authorized-root-test
  (let [app (app-runtime/create (survey-project))]
    (testing "absolute paths are rejected"
      (is (denied? app "(project/list \"/etc\")"))
      (is (denied? app "(project/list \"/\")")))
    (testing "traversal above the root is rejected lexically"
      (is (denied? app "(project/list \"..\")"))
      (is (denied? app "(project/list \"../..\")"))
      (is (denied? app "(project/list \"src/../..\")")))
    (testing "a symbolic link is never followed, even to a legitimate target"
      ;; Reported as a link by the listing, and refused as a traversal step.
      (is (denied? app "(project/list \"escape\")"))
      (is (denied? app "(project/list \"inside\")")
          "refusing an inside link too keeps the rule simple and total")
      (is (denied? app "(project/list \"inside/example\")")))
    (testing "non-directories and absent paths fail rather than guessing"
      (is (denied? app "(project/list \"README.md\")"))
      (is (denied? app "(project/list \"nope\")"))
      (is (denied? app "(project/list \"src/nope/deeper\")")))
    (testing "malformed arguments are rejected"
      (is (denied? app "(project/list)"))
      (is (denied? app "(project/list \"\")"))
      (is (denied? app "(project/list \".\" \"extra\")"))
      (is (denied? app "(project/list 7)")))))

(deftest project-list-requires-its-own-grant-test
  (testing "read authority does not imply listing authority"
    (let [app (app-runtime/create (survey-project) :agent/project-read)]
      (is (denied? app "(project/list \".\")"))
      (is (= "bounded project"
             (get-in (app-runtime/evaluate app "(project/read \"README.md\")")
                     [:evaluation :value :value/data]))
          "while reading still works, so the denial is the grant and not the path"))))

(deftest project-list-is-discoverable-test
  (testing "the model can find the operation through ordinary Clojure"
    (let [app (app-runtime/create (survey-project))]
      (is (some #{'project/list}
                (get-in (app-runtime/evaluate app "(apropos \"project\")")
                        [:evaluation :value :value/data])))
      (is (str/includes?
           (str (:out (:evaluation (app-runtime/evaluate app "(doc project/list)"))))
           "project/list")))))
