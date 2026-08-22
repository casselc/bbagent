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

(defn- unrepresentable-symlink
  "Why a link cannot be carried into a worker faithfully, or nil.

   A worker resolves a link inside its own filesystem, not the host's, so a
   link is only faithful when the same name means the same file on both
   sides.

   `:absolute` covers every absolute target, including one that lexically
   lies under the project root.  `/home/me/project/lib/foo` is not
   `/work/lib/foo`: the worker would resolve it against a path that does not
   exist there.  Such a link cannot escape anywhere — it lands in the
   machine's own filesystem — but it is not the project the manifest
   describes, and A3a refuses to describe a tree it is not handing over.

   `:escaping` covers a relative target that climbs out of the tree.  Its
   resolution inside the worker is equally not what the host has."
  [^Path root ^Path link]
  (let [target (Files/readSymbolicLink link)]
    (cond
      (.isAbsolute target) :absolute
      (not (.startsWith (.normalize (.resolve (.getParent link) target)) root))
      :escaping)))

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
           excluded []
           total-bytes 0]
      (if-not (seq pending)
        {:entries entries :excluded excluded :bytes total-bytes}
        (let [^Path child (peek pending)
              pending (pop pending)]
          (if (contains? exclusions (str (.getFileName child)))
            ;; Named, not just skipped.  A worker hides exactly the paths
            ;; this walk refused to describe, so "excluded from the
            ;; coordinate" and "not visible to the workload" are one set
            ;; rather than two lists that have to be kept in agreement.
            (recur pending entries (conj excluded (str (.relativize root child)))
                   total-bytes)
            (let [relative (str (.relativize root child))
                  ;; Hinted, not inferred.  Without it the .size call below
                  ;; is a reflective lookup on a JDK-internal implementation
                  ;; class, which resolves on the JVM and fails in the
                  ;; native image.
                  ^BasicFileAttributes attributes
                  (Files/readAttributes child BasicFileAttributes no-follow)
                  kind (entry-kind attributes)]
              ;; Checked before the entry is added rather than at the top of
              ;; the next iteration.  The looser form let a terminal entry
              ;; land one past the stated maximum, because nothing came
              ;; after it to notice.
              (when (>= (count entries) max-entries)
                (fail! "Project input exceeds the snapshot entry limit"
                       {:snapshot/max-entries max-entries
                        :snapshot/path relative}))
              (case kind
                :symlink
                (do
                  (when-let [reason (unrepresentable-symlink root child)]
                    (fail! (if (= :absolute reason)
                             (str "Project input contains an absolute "
                                  "symbolic link; the worker resolves an "
                                  "absolute path inside its own filesystem, "
                                  "where it names something else, so this "
                                  "tree cannot be handed over faithfully")
                             (str "Project input contains a symbolic link "
                                  "pointing outside the project root; the "
                                  "worker cannot be given a faithful copy of "
                                  "this tree"))
                           {:snapshot/path relative
                            :snapshot/symlink reason
                            :snapshot/target (str (Files/readSymbolicLink child))}))
                  (recur pending
                         (conj entries
                               {:path relative
                                :kind :symlink
                                :target (str (Files/readSymbolicLink child))})
                         excluded
                         total-bytes))

                :directory
                (recur (into pending (children child))
                       (conj entries {:path relative :kind :directory})
                       excluded
                       total-bytes)

                :file
                (let [bytes (.size attributes)]
                  (when (> (+ total-bytes bytes) max-bytes)
                    (fail! "Project input exceeds the snapshot byte limit"
                           {:snapshot/max-bytes max-bytes
                            :snapshot/path relative}))
                  (recur pending
                         (conj entries
                               {:path relative
                                :kind :file
                                :bytes bytes
                                :digest (str "sha256:"
                                             (coordinates/sha-256-path child))})
                         excluded
                         (+ total-bytes bytes)))

                (recur pending
                       (conj entries {:path relative :kind :other})
                       excluded
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
         {:keys [entries excluded bytes]} (walk path exclusions limits)
         entries (vec (sort-by :path entries))
         manifest {:snapshot/root (str path)
                   :snapshot/exclusions (vec (sort exclusions))
                   ;; The paths the walk actually refused, not the names it
                   ;; was told to refuse.  A worker that hides these hides
                   ;; everything this coordinate does not describe.
                   :snapshot/excluded-paths (vec (sort excluded))
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
