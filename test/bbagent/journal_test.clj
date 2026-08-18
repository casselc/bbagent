(ns bbagent.journal-test
  (:require [bbagent.journal :as journal]
            [bbagent.coordinates :as coordinates]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption StandardOpenOption]
           [java.util UUID]))

(defn- temp-root []
  (str (Files/createTempDirectory
        "bbagent-journal-test"
        (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest session-path-containment-test
  (let [root (temp-root)]
    (doseq [session-id ["." ".." "../foo" "foo/../bar"]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (journal/session-path root session-id))))
    (doseq [session-id ["valid-session"
                        "123e4567-e89b-12d3-a456-426614174000"]]
      (is (= session-id
             (str (.getFileName (journal/session-path root session-id))))))))

(deftest ordered-append-and-correlation-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        request-id (str (UUID/randomUUID))]
    (journal/append! store {:event/type :model/request :request/id request-id})
    (journal/append! store {:event/type :model/response :request/id request-id})
    (let [events (journal/read-events root session-id)]
      (is (= [1 2] (mapv :event/seq events)))
      (is (= [request-id request-id] (mapv :request/id events))))))

(deftest truncated-tail-recovery-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        _ (journal/append! store {:event/type :session/started})
        path (:path store)]
    (Files/write path
                 (.getBytes "{:journal/version 1" StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [reopened (journal/open! root session-id)]
      (is (= 1 (count (journal/events reopened))))
      (is (= :session/started (:event/type (first (journal/events reopened))))))))

(deftest complete-corruption-is-not-tail-recovery-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        _ (journal/append! store {:event/type :session/started})]
    (Files/write (:path store)
                 (.getBytes "{:malformed true}\n" StandardCharsets/UTF_8)
                 (into-array OpenOption [StandardOpenOption/APPEND]))
    (let [failure (try (journal/open! root session-id)
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :session-recovery-failure
             (:bbagent/error (ex-data failure)))))))

(deftest large-value-blob-round-trip-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        content (apply str (repeat 70000 "x"))]
    (journal/append! store {:event/type :model/response :content content})
    (is (= content (:content (first (journal/read-events root session-id)))))
    (is (not (.contains (slurp (str (:path store))) content)))))

(deftest blob-marker-does-not-collide-with-map-data-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        ordinary-map {:blob/digest "sha256:not-a-reference"
                      :blob/bytes 7
                      :blob/encoding :utf-8}]
    (journal/append! store {:event/type :model/response :content ordinary-map})
    (is (= ordinary-map
           (:content (first (journal/read-events root session-id)))))))

(deftest partial-existing-blob-is-replaced-atomically-test
  (let [root (temp-root)
        session-id (str (UUID/randomUUID))
        store (journal/open! root session-id)
        content (apply str (repeat 70000 "z"))
        digest (coordinates/sha-256 content)
        blob-path (.resolve (:blobs store) digest)]
    (Files/write blob-path (.getBytes "partial" StandardCharsets/UTF_8)
                 (make-array OpenOption 0))
    (journal/append! store {:event/type :model/response :content content})
    (is (= content (:content (first (journal/read-events root session-id)))))))

(deftest session-id-and-secret-test
  (let [root (temp-root)
        session-id "stable-session"
        store (journal/open! root session-id)]
    (journal/append! store {:event/type :model/request
                            :api-key "do-not-store"
                            :headers {:authorization "Bearer do-not-store"}
                            :usage {:completion_tokens 12}
                            :safe "kept"})
    (is (= [session-id] (journal/list-sessions root)))
    (let [event (first (journal/read-events root session-id))]
      (is (= "kept" (:safe event)))
      (is (= 12 (get-in event [:usage :completion_tokens])))
      (is (nil? (:api-key event)))
      (is (= {} (:headers event)))
      (is (not (.contains (slurp (str (:path store))) "do-not-store"))))))
