#!/usr/bin/env bb
(ns a3a-source-check
  "Two project invariants, checked without a JVM.

   This is a real check rather than a fixture: the second invariant is one
   this repository actually violated while A3a was being written, when two
   new test namespaces existed, passed in isolation, and were not part of
   `clojure -M:test` because nobody had added them to the runner.

   It is also the A3a dogfood.  Babashka needs no project dependencies and
   no network, so the worker can run it against the real working tree with
   nothing mounted but the project itself."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- clojure-sources [directory]
  (->> (file-seq (io/file directory))
       (filter #(.isFile %))
       (filter #(re-find #"\.cljc?$" (.getName %)))
       (sort-by #(.getPath %))))

(defn- declared-namespace [file]
  (with-open [reader (java.io.PushbackReader. (io/reader file))]
    (loop [remaining 40]
      (when (pos? remaining)
        (let [form (try (read {:eof ::eof :read-cond :preserve} reader)
                        (catch Exception _ ::unreadable))]
          (cond
            (= ::eof form) nil
            (= ::unreadable form) nil
            (and (list? form) (= 'ns (first form))) (second form)
            :else (recur (dec remaining))))))))

(defn- expected-namespace [root file]
  (-> (str (.relativize (.toPath (io/file root)) (.toPath file)))
      (str/replace #"\.cljc?$" "")
      (str/replace "_" "-")
      (str/replace "/" ".")
      symbol))

(defn- namespace-mismatches [root]
  (keep (fn [file]
          (let [declared (declared-namespace file)
                expected (expected-namespace root file)]
            (when (and declared (not= declared expected))
              {:file (str file) :declared declared :expected expected})))
        (clojure-sources root)))

(defn- unregistered-tests []
  (let [runner (slurp "test/bbagent/test_runner.clj")
        declared (->> (clojure-sources "test")
                      (keep declared-namespace)
                      (filter #(str/ends-with? (str %) "-test"))
                      sort)]
    (remove #(str/includes? runner (str %)) declared)))

(defn -main [& _]
  (let [mismatched (concat (namespace-mismatches "src")
                           (namespace-mismatches "test"))
        unregistered (unregistered-tests)]
    (doseq [{:keys [file declared expected]} mismatched]
      (println "namespace mismatch:" file "declares" declared "expected" expected))
    (doseq [namespace unregistered]
      (println "test namespace not registered in the runner:" namespace))
    (println "checked" (count (clojure-sources "src")) "source and"
             (count (clojure-sources "test")) "test files")
    (if (and (empty? mismatched) (empty? unregistered))
      (do (println "a3a-source-check OK") (System/exit 0))
      (do (println "a3a-source-check FAILED") (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
