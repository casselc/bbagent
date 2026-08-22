(ns bbagent.snapshot-test
  (:require [bbagent.snapshot :as snapshot]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files Path Paths]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-root [label]
  (str (Files/createTempDirectory (str "bbagent-snapshot-" label)
                                  (make-array FileAttribute 0))))

(defn- spit! [root relative content]
  (let [file (io/file root relative)]
    (io/make-parents file)
    (spit file content)
    file))

(defn- link! [root relative target]
  (let [^Path link (Paths/get (str (io/file root relative))
                              (make-array String 0))
        ^Path target (Paths/get (str target) (make-array String 0))]
    (Files/createSymbolicLink link target (make-array FileAttribute 0))))

(deftest a-manifest-names-every-entry-test
  (let [root (temp-root "entries")]
    (spit! root "README.md" "hello")
    (spit! root "src/core.clj" "(ns core)")
    (.mkdirs (io/file root "empty"))
    (let [manifest (snapshot/manifest root)
          by-path (into {} (map (juxt :path identity))
                        (:snapshot/entries manifest))]
      (is (= ["README.md" "empty" "src" "src/core.clj"]
             (mapv :path (:snapshot/entries manifest))))
      (is (= :file (:kind (by-path "README.md"))))
      (is (= 5 (:bytes (by-path "README.md"))))
      (is (re-matches #"sha256:[0-9a-f]{64}" (:digest (by-path "README.md"))))
      (testing "a directory with no files is still part of the input"
        (is (= :directory (:kind (by-path "empty"))))))))

(deftest a-coordinate-is-stable-and-sensitive-test
  (let [root (temp-root "stability")]
    (spit! root "a.txt" "one")
    (spit! root "b.txt" "two")
    (let [first-pass (snapshot/coordinate root)]
      (testing "the same tree digests the same way twice"
        (is (= first-pass (snapshot/coordinate root))))
      (testing "one changed byte changes the coordinate"
        (spit! root "a.txt" "ONE")
        (is (not= first-pass (snapshot/coordinate root))))
      (testing "restoring the byte restores the coordinate"
        (spit! root "a.txt" "one")
        (is (= first-pass (snapshot/coordinate root))))
      (testing "an added file changes the coordinate"
        (spit! root "c.txt" "three")
        (is (not= first-pass (snapshot/coordinate root)))))))

(deftest a-coordinate-does-not-depend-on-enumeration-order-test
  ;; Two trees with the same content, created in opposite orders, must
  ;; produce the same coordinate: a manifest that inherited the order a
  ;; filesystem happened to return would identify them as different states.
  (let [forward (temp-root "forward")
        backward (temp-root "backward")]
    (doseq [name ["a.txt" "b.txt" "c.txt"]]
      (spit! forward name name))
    (doseq [name ["c.txt" "b.txt" "a.txt"]]
      (spit! backward name name))
    (is (= (:snapshot/coordinate
            (dissoc (snapshot/manifest forward) :snapshot/root))
           (:snapshot/coordinate
            (dissoc (snapshot/manifest backward) :snapshot/root))))))

(deftest a-coordinate-ignores-the-root-path-test
  ;; The same project copied to a different directory is the same project
  ;; state.  The coordinate names contents, not where they happen to live.
  (let [here (temp-root "here")
        there (temp-root "there")]
    (spit! here "a.txt" "same")
    (spit! there "a.txt" "same")
    (is (= (snapshot/coordinate here) (snapshot/coordinate there)))))

(deftest uncommitted-work-is-part-of-the-input-test
  ;; The whole reason the coordinate is not a git revision.
  (let [root (temp-root "uncommitted")]
    (spit! root "src/core.clj" "(ns core)")
    (.mkdirs (io/file root ".git"))
    (spit! root ".git/HEAD" "ref: refs/heads/main")
    (let [committed (snapshot/coordinate root)]
      (spit! root "src/core.clj" "(ns core) ;; edited but not committed")
      (is (not= committed (snapshot/coordinate root))
          "an edit that git has not seen still changes what a worker runs"))))

(deftest git-metadata-is-excluded-by-default-test
  (let [root (temp-root "git")]
    (spit! root "a.txt" "content")
    (.mkdirs (io/file root ".git"))
    (spit! root ".git/index" "binary-ish")
    (let [manifest (snapshot/manifest root)]
      (is (= ["a.txt"] (mapv :path (:snapshot/entries manifest))))
      (testing "the exclusion is recorded rather than applied silently"
        (is (= [".git"] (:snapshot/exclusions manifest))))
      (testing "git churn does not move the coordinate"
        (let [before (snapshot/coordinate root)]
          (spit! root ".git/index" "different binary-ish")
          (is (= before (snapshot/coordinate root))))))))

(deftest an-internal-symlink-is-described-and-not-followed-test
  (let [root (temp-root "symlink")]
    (spit! root "target.txt" "pointed at")
    (link! root "link.txt" "target.txt")
    (let [entries (:snapshot/entries (snapshot/manifest root))
          link (first (filter #(= "link.txt" (:path %)) entries))]
      (is (= :symlink (:kind link)))
      (is (= "target.txt" (:target link)))
      (testing "the link is not digested as a copy of its target"
        (is (nil? (:digest link)))))))

(deftest an-escaping-symlink-fails-closed-test
  (testing "an absolute link out of the tree"
    (let [root (temp-root "escape-absolute")]
      (spit! root "a.txt" "content")
      (link! root "escape" "/etc/passwd")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pointing outside the project root"
                            (snapshot/manifest root)))))
  (testing "a relative link that climbs out of the tree"
    (let [root (temp-root "escape-relative")]
      (spit! root "src/a.txt" "content")
      (link! root "src/escape" "../../elsewhere")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pointing outside the project root"
                            (snapshot/manifest root))))))

(deftest an-oversized-input-fails-closed-test
  (let [root (temp-root "bounds")]
    (doseq [n (range 12)]
      (spit! root (str "f" n ".txt") "x"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"exceeds the snapshot entry limit"
                          (snapshot/manifest root {:limits {:snapshot/max-entries 3}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"exceeds the snapshot byte limit"
                          (snapshot/manifest root {:limits {:snapshot/max-bytes 2}})))))

(deftest a-missing-root-fails-closed-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"not a readable directory"
                        (snapshot/manifest "/nonexistent/bbagent/project")))
  (let [root (temp-root "not-a-directory")
        file (spit! root "a.txt" "content")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"not a directory"
                          (snapshot/manifest (str file))))))
