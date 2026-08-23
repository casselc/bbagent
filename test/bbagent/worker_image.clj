(ns bbagent.worker-image
  "The guest image the machine-backed tests run against.

   Resolved from the environment when a build already made one, and built
   on demand otherwise, so `clojure -M:test` works from a clean checkout at
   the cost of one image build.  Test-only: nothing in src reaches an image
   builder, and the shipped binary cannot build a guest."
  (:require [clojure.java.io :as io])
  (:import [java.nio.file Files LinkOption Path Paths]
           [java.nio.file.attribute FileAttribute]))

(def ^:private cache
  (str (io/file (System/getProperty "java.io.tmpdir")
                "bbagent-worker-image.tar")))

(defn- exists? [path]
  (Files/isRegularFile (Paths/get ^String path (make-array String 0))
                       (make-array LinkOption 0)))

(defn- build! [target]
  (let [builder (ProcessBuilder.
                 ^java.util.List ["script/build-worker-image" target])
        _ (.redirectErrorStream builder true)
        process (.start builder)
        output (slurp (.getInputStream process))]
    (when-not (zero? (.waitFor process))
      (throw (ex-info (str "Could not build the worker guest image. The "
                           "machine-backed tests need one; build it with "
                           "script/build-worker-image, or point "
                           "BBAGENT_WORKER_IMAGE at an existing archive.")
                      {:output output})))
    target))

(def path
  "The archive path, built once per JVM if it is not already there."
  (delay
    (let [supplied (System/getenv "BBAGENT_WORKER_IMAGE")]
      (cond
        (and supplied (exists? supplied)) supplied
        (exists? cache) cache
        :else (build! cache)))))
