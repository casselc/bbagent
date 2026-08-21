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
    (is (= :agent/project-develop
           (get-in description [:context/spec :profile]))
        "A2 sessions get the writing surface by default")
    (is (= (app-runtime/capabilities app-runtime/default-profile)
           (get-in description [:context/spec :requested-capabilities])))
    (is (= #{:data/json-read :data/json-write :project/read :project/list
             :project/search :project/stat :project/edit}
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

;; ---------------------------------------------------------------------------
;; project/search authority boundary
;; ---------------------------------------------------------------------------

(defn- search-project []
  (let [^Path root (Files/createTempDirectory
                    "bbagent-search"
                    (make-array java.nio.file.attribute.FileAttribute 0))
        write (fn [rel content]
                (let [p (.resolve root ^String rel)]
                  (Files/createDirectories
                   (.getParent p)
                   (make-array java.nio.file.attribute.FileAttribute 0))
                  (Files/writeString p ^String content
                                     (make-array java.nio.file.OpenOption 0))))]
    (write "README.md" "the needle is here\nand not on this line\n")
    (write "src/deep/inner.clj" "(ns deep.inner)\n;; needle again\n")
    (write "src/other.clj" "nothing of interest\n")
    (write ".hidden/secret.txt" "needle in a hidden place\n")
    ;; Invalid UTF-8, so it must be skipped rather than matched or fatal.
    (Files/write (.resolve root "blob.bin")
                 (byte-array [0x6e 0x65 0x65 0x64 0x6c 0x65 (unchecked-byte 0xff)
                              (unchecked-byte 0xfe)])
                 (make-array java.nio.file.OpenOption 0))
    (Files/createSymbolicLink (.resolve root "escape") (.resolve root "..")
                              (make-array java.nio.file.attribute.FileAttribute 0))
    (str root)))

(defn- found [app source]
  (get-in (app-runtime/evaluate app source) [:evaluation :value :value/data]))

(deftest project-search-finds-matches-with-coordinates-test
  (let [app (app-runtime/create (search-project))]
    (testing "a match carries path, line, and the matching text"
      (is (= [{:path "README.md" :line 1 :text "the needle is here"}
              {:path "src/deep/inner.clj" :line 2 :text ";; needle again"}]
             (found app "(project/search \"needle\")"))
          "sorted by path, and recursing into subdirectories"))
    (testing "a regex is a regex, not a literal"
      (is (= 1 (count (found app "(project/search \"^\\\\(ns \")")))))
    (testing ":path scopes the search to a subtree"
      (is (= ["src/deep/inner.clj"]
             (mapv :path (found app "(project/search \"needle\" {:path \"src\"})")))))
    (testing "no match is an empty result rather than an error"
      (is (= [] (found app "(project/search \"nowherefound\")"))))))

(deftest project-search-skips-what-it-should-test
  (let [app (app-runtime/create (search-project))]
    (testing "dot-entries are skipped by default and reachable on request"
      (is (not-any? #(str/starts-with? (:path %) ".hidden")
                    (found app "(project/search \"needle\")")))
      (is (some #(str/starts-with? (:path %) ".hidden")
                (found app "(project/search \"needle\" {:include-hidden? true})"))))
    (testing "a file that is not valid UTF-8 is skipped, not matched or fatal"
      (is (not-any? #(= "blob.bin" (:path %))
                    (found app "(project/search \"needle\" {:include-hidden? true})"))))
    (testing "symbolic links are not followed"
      (is (not-any? #(str/starts-with? (:path %) "escape")
                    (found app "(project/search \"needle\" {:include-hidden? true})"))))))

(deftest project-search-cannot-leave-the-authorized-root-test
  (let [app (app-runtime/create (search-project))]
    (is (denied? app "(project/search \"needle\" {:path \"/etc\"})"))
    (is (denied? app "(project/search \"needle\" {:path \"..\"})"))
    (is (denied? app "(project/search \"needle\" {:path \"src/../..\"})"))
    (is (denied? app "(project/search \"needle\" {:path \"escape\"})"))
    (is (denied? app "(project/search \"needle\" {:path \"README.md\"})"))
    (is (denied? app "(project/search \"needle\" {:path \"nope\"})"))))

(deftest project-search-rejects-malformed-input-test
  (let [app (app-runtime/create (search-project))]
    (testing "arity and types"
      (is (denied? app "(project/search)"))
      (is (denied? app "(project/search \"\")"))
      (is (denied? app "(project/search 7)"))
      (is (denied? app "(project/search \"needle\" \"not-a-map\")"))
      (is (denied? app "(project/search \"needle\" {} \"extra\")")))
    (testing "an invalid regex fails rather than being treated as a literal"
      (is (denied? app "(project/search \"(unclosed\")")))))

(deftest project-search-bounds-pathological-patterns-test
  (testing "an expensive pattern fails on its budget instead of running"
    ;; Measured, not assumed: Java's matcher is not exponential here. (a+)+$
    ;; against n a's costs about n^2 reads -- 41k at n=200, 1.0M at n=1000 --
    ;; so 200 a's finishes well inside the budget and 1000 does not. The bound
    ;; is on superlinear cost, not on exponential blowup the engine already
    ;; avoids.
    (let [^Path root (Files/createTempDirectory
                      "bbagent-redos"
                      (make-array java.nio.file.attribute.FileAttribute 0))
          _ (Files/writeString (.resolve root "bomb.txt")
                               (str (apply str (repeat 1000 "a")) "!")
                               (make-array java.nio.file.OpenOption 0))
          app (app-runtime/create (str root))
          started (System/currentTimeMillis)
          result (app-runtime/evaluate app "(project/search \"(a+)+$\")")
          elapsed (- (System/currentTimeMillis) started)]
      (is (= :error (:status result))
          "the budget must stop it rather than the pattern finishing")
      (is (= :bb4t-evaluation-failure (:bbagent/error (:error result))))
      (is (< elapsed 30000)
          (str "search must terminate promptly; took " elapsed "ms")))))

(deftest project-search-requires-its-own-grant-test
  (testing "read and list authority do not imply search authority"
    (let [app (app-runtime/create (search-project) :agent/project-read)]
      (is (denied? app "(project/search \"needle\")"))
      (is (= "the needle is here\nand not on this line\n"
             (get-in (app-runtime/evaluate app "(project/read \"README.md\")")
                     [:evaluation :value :value/data]))))))

(deftest composing-over-a-search-result-test
  (testing "the agent can build vocabulary over a capability result"
    ;; The point of A2's expanded base-allow: results are composable in SCI
    ;; rather than needing a new host operation per question.
    (let [app (app-runtime/create (search-project))]
      (app-runtime/evaluate app "(def hits (project/search \"needle\"))")
      (app-runtime/evaluate
       app
       (str "(defn in-dir [ms d] "
            "(filter (fn [m] (clojure.string/starts-with? (:path m) d)) ms))"))
      (is (= 1 (get-in (app-runtime/evaluate app "(count (in-dir hits \"src\"))")
                       [:evaluation :value :value/data])))
      (is (= 2 (get-in (app-runtime/evaluate app "(count hits)")
                       [:evaluation :value :value/data]))))))

(deftest lazy-results-are-visible-test
  (testing "a lazy sequence describes as data rather than as opaque"
    ;; take/map/filter all return lazy seqs; describing those as opaque made
    ;; the expanded vocabulary useless for looking at anything.
    (let [app (app-runtime/create (search-project))
          v (get-in (app-runtime/evaluate
                     app "(map :path (project/search \"needle\"))")
                    [:evaluation :value])]
      (is (= :inert-data (:value/kind v)))
      (is (= ["README.md" "src/deep/inner.clj"] (:value/data v))))))

(deftest string-namespace-is-callable-both-ways-test
  (testing "str/ works, because that is how Clojure is written"
    ;; SCI checks permission against the symbol as written, before alias
    ;; resolution, so the alias alone is not enough and both spellings are
    ;; listed. The A2 dogfood watched the model reach for str/replace and fail.
    (let [app (app-runtime/create (search-project))
          data #(get-in (app-runtime/evaluate app %) [:evaluation :value :value/data])]
      (is (= "OK" (data "(str/upper-case \"ok\")")))
      (is (= "OK" (data "(clojure.string/upper-case \"ok\")")))
      (testing "clojure.core/str is untouched by the alias"
        (is (= "core-str" (data "(str \"core\" \"-str\")"))))
      (testing "the alias grants nothing: an unlisted string fn is still denied"
        (is (denied? app "(str/re-quote-replacement \"x\")"))
        (is (denied? app "(clojure.string/re-quote-replacement \"x\")")))
      (testing "it composes with a capability result"
        (is (= 2 (data (str "(count (str/split-lines "
                            "(project/read \"README.md\")))"))))))))

;; ---------------------------------------------------------------------------
;; project/stat and project/edit: version-anchored mutation
;; ---------------------------------------------------------------------------

(defn- edit-project []
  (let [^Path root (Files/createTempDirectory
                    "bbagent-edit"
                    (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString (.resolve root "a.txt") "original\n"
                       (make-array java.nio.file.OpenOption 0))
    (Files/createDirectories (.resolve root "sub")
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createSymbolicLink (.resolve root "escape") (.resolve root "..")
                              (make-array java.nio.file.attribute.FileAttribute 0))
    (str root)))

(deftest project-stat-reports-a-usable-coordinate-test
  (let [app (app-runtime/create (edit-project))
        stat (found app "(project/stat \"a.txt\")")]
    (is (= "a.txt" (:path stat)))
    (is (= :file (:kind stat)))
    (is (= 9 (:bytes stat)))
    (is (str/starts-with? (:digest stat) "sha256:"))
    (testing "an absent file is reported, not an error"
      (is (= {:path "nope.txt" :kind :absent}
             (found app "(project/stat \"nope.txt\")"))))
    (testing "a directory reports its kind and carries no digest"
      (let [d (found app "(project/stat \"sub\")")]
        (is (= :directory (:kind d)))
        (is (nil? (:digest d)))))))

(deftest project-edit-requires-a-base-coordinate-test
  (testing "an edit without a base is refused rather than overwriting blindly"
    ;; The human, an editor, a formatter and a Git checkout all write to the
    ;; same world. An edit states what it believed and is refused when that is
    ;; no longer true.
    (let [app (app-runtime/create (edit-project))]
      (is (denied? app "(project/edit {:path \"a.txt\" :content \"blind\"})"))
      (is (denied? app "(project/edit {:path \"a.txt\" :base nil :content \"x\"})"))
      (is (denied? app "(project/edit {:path \"a.txt\" :base {} :content \"x\"})"))
      (is (= "original\n"
             (get-in (app-runtime/evaluate app "(project/read \"a.txt\")")
                     [:evaluation :value :value/data]))
          "the file must be untouched by every refusal above"))))

(deftest project-edit-applies-and-conflicts-test
  (let [app (app-runtime/create (edit-project))
        data #(get-in (app-runtime/evaluate app %) [:evaluation :value :value/data])]
    (app-runtime/evaluate app "(def c (project/stat \"a.txt\"))")
    (testing "an edit anchored to the current version applies"
      (let [result (data (str "(project/edit {:path \"a.txt\" "
                              ":base {:digest (:digest c)} "
                              ":content \"rewritten\n\"})"))]
        (is (= "a.txt" (:path result)))
        (is (= 10 (:bytes result)))
        (is (str/starts-with? (:digest result) "sha256:")))
      (is (= "rewritten\n" (data "(project/read \"a.txt\")"))))
    (testing "the same base is now stale, and the second write is refused"
      (is (denied? app (str "(project/edit {:path \"a.txt\" "
                            ":base {:digest (:digest c)} "
                            ":content \"again\n\"})")))
      (is (= "rewritten\n" (data "(project/read \"a.txt\")"))
          "a conflict must leave the file exactly as it was"))
    (testing "the digest an edit returns anchors the next edit"
      (app-runtime/evaluate app "(def c2 (project/stat \"a.txt\"))")
      (is (some? (data (str "(project/edit {:path \"a.txt\" "
                            ":base {:digest (:digest c2)} "
                            ":content \"third\n\"})"))))
      (is (= "third\n" (data "(project/read \"a.txt\")"))))))

(deftest project-edit-creates-only-with-absent-base-test
  (let [app (app-runtime/create (edit-project))
        data #(get-in (app-runtime/evaluate app %) [:evaluation :value :value/data])]
    (testing ":absent creates a file that does not exist"
      (is (some? (data "(project/edit {:path \"b.txt\" :base :absent :content \"new\"})")))
      (is (= "new" (data "(project/read \"b.txt\")"))))
    (testing ":absent on an existing file is a conflict, not a truncation"
      (is (denied? app "(project/edit {:path \"b.txt\" :base :absent :content \"twice\"})"))
      (is (= "new" (data "(project/read \"b.txt\")"))))
    (testing "a digest base on an absent file is a conflict"
      (is (denied? app (str "(project/edit {:path \"gone.txt\" "
                            ":base {:digest \"sha256:00\"} :content \"x\"})"))))
    (testing "it does not create directories"
      (is (denied? app "(project/edit {:path \"new/deep.txt\" :base :absent :content \"x\"})")))))

(deftest project-edit-cannot-leave-the-authorized-root-test
  (let [app (app-runtime/create (edit-project))]
    (is (denied? app "(project/edit {:path \"../escaped\" :base :absent :content \"x\"})"))
    (is (denied? app "(project/edit {:path \"/tmp/escaped\" :base :absent :content \"x\"})"))
    (is (denied? app "(project/edit {:path \"sub/../../escaped\" :base :absent :content \"x\"})"))
    (is (denied? app "(project/edit {:path \"escape/x\" :base :absent :content \"x\"})"))
    (is (denied? app "(project/edit {:path \".\" :base :absent :content \"x\"})"))
    (is (denied? app "(project/edit {:path \"sub\" :base :absent :content \"x\"})")
        "a directory is not a regular file")
    (testing "malformed input"
      (is (denied? app "(project/edit)"))
      (is (denied? app "(project/edit \"a.txt\")"))
      (is (denied? app "(project/edit {:path \"a.txt\" :base :absent :content 7})")))))

(deftest write-authority-is-a-separate-profile-test
  (testing "the surveying profile can read the world but not change it"
    (let [app (app-runtime/create (edit-project) :agent/project-survey)]
      (is (some? (get-in (app-runtime/evaluate app "(project/stat \"a.txt\")")
                         [:evaluation :value :value/data])))
      (is (denied? app "(project/edit {:path \"a.txt\" :base :absent :content \"x\"})"))))
  (testing "the frozen A0 profile has neither"
    (let [app (app-runtime/create (edit-project) :agent/project-read)]
      (is (denied? app "(project/stat \"a.txt\")"))
      (is (denied? app "(project/edit {:path \"a.txt\" :base :absent :content \"x\"})")))))
