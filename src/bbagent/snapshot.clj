(ns bbagent.snapshot
  "What a worker was given, named by a coordinate.

   A worker runs against the project as it exists now, uncommitted edits
   included, so the thing that identifies an execution cannot be a git
   revision.  It is a manifest of the authorized project tree: every path,
   its kind, and for a file its size and content digest.

   The manifest is the honest half of a verification claim.  Without it a
   result says a command passed; with it a result says a command passed
   against exactly this project state."
  (:require [bbagent.coordinates :as coordinates])
  (:import [java.io IOException]
           [java.nio.file Files LinkOption Path Paths]
           [java.nio.file.attribute BasicFileAttributes]))

(def ^:private ^"[Ljava.nio.file.LinkOption;" no-follow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(def default-exclusions
  "Directory names excluded from a project input by default.

   `.git` is excluded for two reasons, neither of which is that it is large.
   Git writes into it on operations that look like reads, so including it
   makes the input coordinate change while nothing a build cares about
   changed; and A3a deliberately does not make git available to the worker,
   so shipping the object store in would be shipping something unusable."
  #{".git"})

(def default-limits
  {:snapshot/max-entries 100000
   :snapshot/max-bytes 2147483648})

(defn- fail! [message data]
  (throw (ex-info message (assoc data :bbagent/error :snapshot-invalid))))

(defn- entry-kind [^BasicFileAttributes attributes]
  (cond
    (.isSymbolicLink attributes) :symlink
    (.isDirectory attributes) :directory
    (.isRegularFile attributes) :file
    :else :other))

(defn- escaping-symlink?
  "Whether a link's target names something outside the project tree.

   Such a link is refused rather than recorded.  The worker resolves it
   inside its own filesystem, where the same name means something else, so
   the manifest would be describing a file the worker never sees."
  [^Path root ^Path link]
  (let [target (Files/readSymbolicLink link)
        resolved (.normalize (if (.isAbsolute target)
                               target
                               (.resolve (.getParent link) target)))]
    (not (.startsWith resolved root))))

(defn- children
  "The immediate entries of a directory, as a vector."
  [^Path directory]
  (with-open [stream (Files/newDirectoryStream directory)]
    (vec (iterator-seq (.iterator stream)))))

(defn- walk
  "Collects manifest entries for everything under the project root.

   Symbolic links are described and never followed, matching what the
   project capabilities themselves do: A2 refuses to traverse a link, and a
   snapshot that quietly traversed one would widen that boundary from
   underneath it.  Traversal order is not significant; entries are sorted by
   path before they are digested."
  [^Path root exclusions limits]
  (let [max-entries (:snapshot/max-entries limits)
        max-bytes (:snapshot/max-bytes limits)]
    (loop [pending (children root)
           entries []
           total-bytes 0]
      (if-not (seq pending)
        {:entries entries :bytes total-bytes}
        (let [^Path child (peek pending)
              pending (pop pending)]
          (when (> (count entries) max-entries)
            (fail! "Project input exceeds the snapshot entry limit"
                   {:snapshot/max-entries max-entries}))
          (when (> total-bytes max-bytes)
            (fail! "Project input exceeds the snapshot byte limit"
                   {:snapshot/max-bytes max-bytes}))
          (if (contains? exclusions (str (.getFileName child)))
            (recur pending entries total-bytes)
            (let [relative (str (.relativize root child))
                  ;; Hinted, not inferred.  Without it the .size call below
                  ;; is a reflective lookup on a JDK-internal implementation
                  ;; class, which resolves on the JVM and fails in the
                  ;; native image.
                  ^BasicFileAttributes attributes
                  (Files/readAttributes child BasicFileAttributes no-follow)
                  kind (entry-kind attributes)]
              (case kind
                :symlink
                (do
                  (when (escaping-symlink? root child)
                    (fail! (str "Project input contains a symbolic link "
                                "pointing outside the project root; the "
                                "worker cannot be given a faithful copy of "
                                "this tree")
                           {:snapshot/path relative}))
                  (recur pending
                         (conj entries
                               {:path relative
                                :kind :symlink
                                :target (str (Files/readSymbolicLink child))})
                         total-bytes))

                :directory
                (recur (into pending (children child))
                       (conj entries {:path relative :kind :directory})
                       total-bytes)

                :file
                (let [bytes (.size attributes)]
                  (recur pending
                         (conj entries
                               {:path relative
                                :kind :file
                                :bytes bytes
                                :digest (str "sha256:"
                                             (coordinates/sha-256-path child))})
                         (+ total-bytes bytes)))

                (recur pending
                       (conj entries {:path relative :kind :other})
                       total-bytes)))))))))

(defn manifest
  "The project input manifest and its coordinate.

   Entries are sorted by path, so the coordinate does not depend on the
   order a filesystem happened to enumerate.  Exclusions are recorded rather
   than applied silently, because a coordinate that quietly omits part of a
   tree describes a project that does not exist."
  ([root] (manifest root nil))
  ([root {:keys [exclusions limits]}]
   (let [exclusions (or exclusions default-exclusions)
         limits (merge default-limits limits)
         ^Path path (try
                      (.toRealPath (Paths/get (str root) (make-array String 0))
                                   (make-array LinkOption 0))
                      (catch IOException failure
                        (fail! "Project root is not a readable directory"
                               {:snapshot/root (str root)
                                :error/message (.getMessage failure)})))
         _ (when-not (Files/isDirectory path (make-array LinkOption 0))
             (fail! "Project root is not a directory" {:snapshot/root (str path)}))
         {:keys [entries bytes]} (walk path exclusions limits)
         entries (vec (sort-by :path entries))
         manifest {:snapshot/root (str path)
                   :snapshot/exclusions (vec (sort exclusions))
                   :snapshot/entries entries
                   :snapshot/entry-count (count entries)
                   :snapshot/bytes bytes}]
     (assoc manifest
            :snapshot/coordinate
            (coordinates/digest :project/input
                                (select-keys manifest
                                             [:snapshot/exclusions
                                              :snapshot/entries]))))))

(defn coordinate
  "Just the coordinate, for bracketing an execution."
  ([root] (coordinate root nil))
  ([root options] (:snapshot/coordinate (manifest root options))))
