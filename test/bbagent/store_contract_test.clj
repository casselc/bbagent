(ns bbagent.store-contract-test
  "Deterministic differential file-vs-SQLite store contract.

  One parameterized logical contract is exercised against both the :file
  and :sqlite backends opened through bbagent.storage/open!, driven only
  through the public bbagent.store protocols and the public session API.
  Every observation must be logically identical across backends.

  Determinism strategy: the store-level event sequence carries only
  caller-supplied :event/id and :event/time values (so the compared
  streams need no exclusions at all), backend-assigned identities are
  compared only through shape projections, and session-API flows are
  compared through a projection that excludes run-generated event IDs,
  times, run IDs, request IDs, and bb4t event payloads.  Failure probes
  are compared at the semantic :bbagent/error category level -- the two
  stores may fail through different physical mechanisms, but must fail
  closed with a stable semantic identity.  Every store/session is closed
  in a finally block (all closes used here are idempotent)."
  (:require [bbagent.bb4t :as bb4t]
            [bbagent.coordinates :as coordinates]
            [bbagent.provider :as provider]
            [bbagent.session :as session]
            [bbagent.storage :as storage]
            [bbagent.store :as store]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(def ^:private backends [:file :sqlite])

(def ^:private fail-closed-categories
  "Error categories that constitute failing closed at the store boundary.
  Digest probes assert membership here rather than one exact category,
  because the file and SQLite stores reject them at different layers."
  #{:journal-storage-failure :session-recovery-failure})

(defn- temp-root [prefix]
  (str (Files/createTempDirectory
        prefix (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- fixture-project []
  (let [^Path root (Files/createTempDirectory
                    "bbagent-store-contract-project"
                    (make-array java.nio.file.attribute.FileAttribute 0))]
    (Files/writeString (.resolve root "README.md")
                       "store contract fixture project"
                       (make-array java.nio.file.OpenOption 0))
    (str root)))

(defn- utf-8-bytes ^long [^String value]
  (alength (.getBytes value StandardCharsets/UTF_8)))

(defn- error-category [thunk]
  (try (thunk) ::succeeded
       (catch clojure.lang.ExceptionInfo failure
         (:bbagent/error (ex-data failure)))))

(defn- fail-closed [category]
  (if (contains? fail-closed-categories category) ::fail-closed category))

(def ^:private sensitive-name-pattern
  "Test-side mirror of the journal's sensitive-key shape, used only to
  scan read-back events for surviving sensitive keys."
  #"(?i)^(api[-_]?key|authorization|credentials?|password|secret|token|access[-_]?token|refresh[-_]?token|oauth[-_]?token)$")

(defn- any-sensitive-keys? [value]
  (cond
    (map? value)
    (boolean (some (fn [[key item]]
                     (or (re-find sensitive-name-pattern
                                  (if (keyword? key) (name key) (str key)))
                         (any-sensitive-keys? item)))
                   value))
    (sequential? value) (boolean (some any-sensitive-keys? value))
    (set? value) (boolean (some any-sensitive-keys? value))
    :else false))

;; ---------------------------------------------------------------------------
;; Deterministic logical fixtures (valid by construction; no randomness).
;; ---------------------------------------------------------------------------

(def ^:private alpha-session-id "alpha-session")
(def ^:private auto-session-id "auto-session")
(def ^:private sigma-session-id "sigma-secrets")

(def ^:private large-content
  "66000 UTF-8 bytes through multibyte characters: strictly over threshold."
  (apply str (repeat 33000 "\u03bb")))

(def ^:private boundary-content
  "Exactly the 65536-byte threshold: must stay inline and still roundtrip."
  (apply str (repeat 65536 "a")))

(def ^:private object-small "differential object payload (identical on every backend)")
(def ^:private object-large (apply str (repeat 33000 "\u03c9")))

(def ^:private alpha-events
  [{:event/type :session/started
    :event/id "evt-a-01"
    :event/time "2026-01-01T00:00:01Z"
    :session/coordinate {:session/id alpha-session-id
                         :agent/profile :a0/single-agent}}
   {:event/type :repl/request
    :event/id "evt-a-02"
    :event/time "2026-01-01T00:00:02Z"
    :request/id "req-1"
    :action/id "act-1"
    :repl/source "(+ 1 2)"}
   {:event/type :repl/result
    :event/id "evt-a-03"
    :event/time "2026-01-01T00:00:03Z"
    :request/id "req-1"
    :action/id "act-1"
    :repl/result {:status :ok
                  :evaluation {:value {:value/kind :inert-data :value/data 3}
                               :out ""
                               :err ""}}}
   {:event/type :session/checkpoint
    :event/id "evt-a-04"
    :event/time "2026-01-01T00:00:04Z"
    :checkpoint/reason :repl-result
    :session/messages [{:role :user :content "compute"}
                       {:role :tool :action/id "act-1" :content "{:status :ok}"}]
    :repl/replay-forms [{:source "(+ 1 2)" :expected-status :ok}]}
   {:event/type :model/response
    :event/id "evt-a-05"
    :event/time "2026-01-01T00:00:05Z"
    :response/status :ok
    :content large-content}
   {:event/type :model/response
    :event/id "evt-a-06"
    :event/time "2026-01-01T00:00:06Z"
    :response/status :ok
    :content boundary-content}
   {:event/type :session/checkpoint
    :event/id "evt-a-07"
    :event/time "2026-01-01T00:00:07Z"
    :checkpoint/reason :model-finish}])

(def ^:private alpha-reopen-event
  {:event/type :session/ended
   :event/id "evt-a-08"
   :event/time "2026-01-01T00:00:08Z"
   :session/end-reason :contract-complete
   :run/id "run-alpha-fixed"})

(def ^:private alpha-expected
  (mapv (fn [index event] (assoc event :event/seq (inc index)))
        (range) alpha-events))

(def ^:private alpha-final-expected
  (conj alpha-expected (assoc alpha-reopen-event :event/seq 8)))

(def ^:private sigma-input
  {:event/type :model/request
   :event/id "evt-s-01"
   :event/time "2026-01-02T00:00:01Z"
   :api-key "sigma-leak"
   :headers {:authorization "Bearer sigma-leak" :content-type "keep/header"}
   :credentials "sigma-leak"
   :nested {:password "sigma-leak" :safe {:secret "sigma-leak" :keep 7}}
   :vector [{:token "sigma-leak"} :keep/vector]
   :ordered (list {:access_token "sigma-leak"} :keep/list)
   :set-of #{{:refresh-token "sigma-leak"} :keep/set}
   :string-keyed {"OAUTH-TOKEN" "sigma-leak" "plain" "keep/string"}
   :chars \u03bb
   :kept "kept-value"})

(def ^:private sigma-expected
  (-> sigma-input
      (dissoc :api-key :credentials)
      (assoc :headers {:content-type "keep/header"}
             :nested {:safe {:keep 7}}
             :vector [{} :keep/vector]
             :ordered (list {} :keep/list)
             :set-of #{{} :keep/set}
             :string-keyed {"plain" "keep/string"})))

(def ^:private expected-listing
  ["alpha-session" "auto-session" "bravo-session" "delta-session" "sigma-secrets"])

;; ---------------------------------------------------------------------------
;; The parameterized logical store contract.
;; ---------------------------------------------------------------------------

(defn- exercise-logical-contract!
  "Runs the whole logical store contract against one backend, asserting
  per-backend invariants, and returns the normalized observations that
  must be identical for :file and :sqlite."
  [backend]
  (let [state-root (temp-root (str "bbagent-store-contract-" (name backend)))
        store (storage/open! state-root backend)]
    (try
      (testing "fixtures are correct by construction"
        (is (> (utf-8-bytes large-content) store/blob-threshold-bytes))
        (is (= (utf-8-bytes boundary-content) store/blob-threshold-bytes)))

      (testing "ordered append keeps caller IDs/times; sequences are assigned"
        (doseq [event alpha-events]
          (let [stored (store/append-event! store alpha-session-id event)]
            (is (= (:event/id event) (:event/id stored)))
            (is (= (:event/time event) (:event/time stored)))))
        (let [alpha-stream (store/events store alpha-session-id)]
          (is (= (mapv :event/id alpha-events) (mapv :event/id alpha-stream)))
          (is (= (mapv :event/time alpha-events) (mapv :event/time alpha-stream)))
          (is (= [1 2 3 4 5 6 7] (mapv :event/seq alpha-stream)))
          (is (= alpha-expected alpha-stream))
          (is (= large-content (:content (nth alpha-stream 4)))
              "oversized multibyte UTF-8 content roundtrips through the event")
          (is (= boundary-content (:content (nth alpha-stream 5)))
              "threshold-sized content roundtrips")))

      (testing "backend-assigned IDs and times when the caller omits them"
        (let [assigned-1 (store/append-event! store auto-session-id
                                              {:event/type :session/started})
              assigned-2 (store/append-event! store auto-session-id
                                              {:event/type :user/message
                                               :message/content "backend-assigned"})
              auto-stream (store/events store auto-session-id)
              auto-projection (mapv (juxt :event/type :event/seq) auto-stream)]
          (is (every? #(and (string? (:event/id %))
                            (not (str/blank? (:event/id %))))
                      auto-stream))
          (is (every? #(and (string? (:event/time %))
                            (not (str/blank? (:event/time %))))
                      auto-stream))
          (is (not= (:event/id assigned-1) (:event/id assigned-2)))
          (is (= [[:session/started 1] [:user/message 2]] auto-projection)
              "assigned identities are excluded; types and sequences are kept")))

      (testing "recursive secret stripping at every depth"
        (store/append-event! store sigma-session-id sigma-input)
        (let [sigma-stream (store/events store sigma-session-id)]
          (is (= [(assoc sigma-expected :event/seq 1)] sigma-stream))
          (is (not (any-sensitive-keys? sigma-stream)))
          (is (not (str/includes? (pr-str sigma-stream) "sigma-leak"))
              "stripped secrets survive nowhere in the logical stream")))

      ;; Sessions appended out of sorted order to exercise the listing.
      (store/append-event! store "delta-session"
                           {:event/type :session/started
                            :event/id "evt-d-01"
                            :event/time "2026-01-03T00:00:01Z"})
      (store/append-event! store "bravo-session"
                           {:event/type :session/started
                            :event/id "evt-b-01"
                            :event/time "2026-01-03T00:00:02Z"})

      (testing "content-addressed objects: put, get, duplicate CAS insertion"
        (let [small-reference (store/put-object! store alpha-session-id object-small)
              large-reference (store/put-object! store alpha-session-id object-large)
              small-again (store/put-object! store alpha-session-id object-small)
              small-digest (:digest (:form small-reference))
              large-digest (:digest (:form large-reference))]
          (is (store/blob-reference? small-reference))
          (is (= #{:digest :bytes :encoding} (set (keys (:form small-reference)))))
          (is (= :utf-8 (:encoding (:form small-reference))))
          (is (= (utf-8-bytes object-small) (:bytes (:form small-reference))))
          (is (= (str "sha256:" (coordinates/sha-256 object-small)) small-digest))
           (is (= (:form small-reference) (:form small-again))
               "duplicate CAS insertion of identical content is idempotent")
           (is (= object-small (store/get-object store alpha-session-id small-digest)))
           (is (= object-large (store/get-object store alpha-session-id large-digest)))
           (is (= object-small (store/get-object store "cross-session" small-digest))
               "content identity is state-root-wide on both backends")
           (store/append-event! store "delta-session"
                                {:event/type :model/response
                                 :event/id "evt-d-02"
                                 :event/time "2026-01-03T00:00:03Z"
                                 :content small-reference})
           (is (= object-small
                  (:content (second (store/events store "delta-session")))))))

       (testing "caller-supplied dangling object references cannot commit"
         (let [dangling (tagged-literal
                         'bbagent/blob
                         {:digest (str "sha256:" (apply str (repeat 64 "0")))
                          :bytes 7
                          :encoding :utf-8})]
           (is (= :journal-storage-failure
                  (error-category
                   #(store/append-event! store "delta-session"
                                         {:event/type :model/response
                                          :event/id "evt-d-03"
                                          :event/time "2026-01-03T00:00:04Z"
                                          :content dangling}))))
           (is (= 2 (count (store/events store "delta-session"))))))

       (testing "blob-shaped map keys and foreign tagged forms are opaque"
         (let [dangling (tagged-literal
                         'bbagent/blob
                         {:digest (str "sha256:" (apply str (repeat 64 "0")))
                          :bytes 7
                          :encoding :utf-8})
               foreign (tagged-literal 'contract/opaque {:dangling dangling})
               opaque-event {:event/type :contract/opaque
                             :event/id "evt-d-04"
                             :event/time "2026-01-03T00:00:05Z"
                             :map-with-blob-key {dangling :kept}
                             :foreign-tag foreign}
               stored (store/append-event! store "delta-session" opaque-event)]
           (is (= opaque-event (dissoc stored :event/seq)))
           (is (= (assoc opaque-event :event/seq 3)
                  (peek (store/events store "delta-session"))))))

       (testing "objects alone do not create a logical session"
         (store/put-object! store "object-only" "unreferenced object")
         (is (not (some #{"object-only"} (store/list-sessions store)))))

      (testing "duplicate event ID insertion fails closed without side effects"
        (let [category (error-category
                        #(store/append-event! store alpha-session-id
                                              {:event/id "evt-a-01"
                                               :event/time "2026-01-01T00:00:09Z"
                                               :event/type :model/request}))]
          (is (= :journal-storage-failure category))
           (is (= 7 (count (store/events store alpha-session-id))))
           (is (= 7 (store/validate-session! store alpha-session-id)))))

       (testing "event IDs are unique across the state root"
         (is (= :journal-storage-failure
                (error-category
                 #(store/append-event! store "other-session"
                                       {:event/id "evt-a-01"
                                        :event/time "2026-01-01T00:00:09Z"
                                        :event/type :session/started}))))
         (is (= [] (store/events store "other-session"))))

      (testing "unknown event IDs fail closed"
        (is (= :journal-storage-failure
               (error-category #(store/events-after store alpha-session-id
                                                     "evt-unknown")))))

      (testing "malformed and unknown object digests fail closed"
        (let [malformed (error-category #(store/get-object store alpha-session-id
                                                           "not-a-digest"))
              short-hex (error-category #(store/get-object store alpha-session-id
                                                           "sha256:deadbeef"))
              missing (error-category #(store/get-object
                                        store alpha-session-id
                                        (str "sha256:" (apply str (repeat 64 "0")))))]
          (is (contains? fail-closed-categories malformed))
          (is (contains? fail-closed-categories short-hex))
          (is (contains? fail-closed-categories missing))))

      (testing "non-string objects are rejected identically"
        (is (= :journal-storage-failure
               (error-category #(store/put-object! store alpha-session-id 42)))))

      (testing "request/result and action correlation"
        (let [request (store/request-event store alpha-session-id "req-1")
              after-request (store/events-after store alpha-session-id "evt-a-02")]
          (is (= "evt-a-02" (:event/id request)))
          (is (= :repl/request (:event/type request)))
          (is (= "act-1" (:action/id request)))
          (is (= "req-1" (:request/id (first after-request))))
          (is (= "act-1" (:action/id (first after-request)))
              "the result immediately after the request carries the same action")
          (is (= [3 4 5 6 7] (mapv :event/seq after-request)))
          (is (= [] (store/events-after store alpha-session-id "evt-a-07")))
          (is (nil? (store/request-event store alpha-session-id "req-none")))))

      (testing "latest checkpoint and first-event selection"
        (let [checkpoint (store/latest-checkpoint store alpha-session-id)]
          (is (= "evt-a-07" (:event/id checkpoint)))
          (is (= :model-finish (:checkpoint/reason checkpoint))))
        (is (= "evt-a-01" (:event/id (store/first-event store alpha-session-id
                                                        :session/started))))
        (is (nil? (store/first-event store alpha-session-id :model/request))))

      (testing "absent sessions are empty, not failures"
        (is (= [] (store/events store "absent-session")))
        (is (= 0 (store/validate-session! store "absent-session")))
        (is (nil? (store/latest-checkpoint store "absent-session")))
        (is (nil? (store/first-event store "absent-session" :session/started))))

      (testing "session listing is sorted and stable"
        (is (= expected-listing (store/list-sessions store)))
        (is (= expected-listing (store/list-sessions store))))

      (testing "close is idempotent; reopen preserves the logical stream"
        (is (nil? (store/close-store! store)))
        (is (nil? (store/close-store! store)))
        (let [reopened (storage/open! state-root backend)]
          (try
            (is (= alpha-expected (store/events reopened alpha-session-id)))
            (is (= "evt-a-07" (:event/id (store/latest-checkpoint reopened
                                                                  alpha-session-id))))
            (let [stored (store/append-event! reopened alpha-session-id
                                              alpha-reopen-event)]
              (is (= "evt-a-08" (:event/id stored)))
              (is (= 8 (:event/seq stored))
                  "sequence numbering continues after reopen"))
            (is (= alpha-final-expected (store/events reopened alpha-session-id)))
            (is (= [8] (mapv :event/seq
                             (store/events-after reopened alpha-session-id
                                                 "evt-a-07"))))
            (is (= 8 (store/validate-session! reopened alpha-session-id)))
            (is (= object-small (store/get-object
                                 reopened alpha-session-id
                                 (str "sha256:" (coordinates/sha-256 object-small)))))
            (is (= expected-listing (store/list-sessions reopened)))
            (finally
              (store/close-store! reopened)))))

      ;; Observations -------------------------------------------------------
      (let [small-digest (str "sha256:" (coordinates/sha-256 object-small))
            large-digest (str "sha256:" (coordinates/sha-256 object-large))]
        {:listing expected-listing
         :alpha-stream alpha-final-expected
         :auto-projection [[:session/started 1] [:user/message 2]]
         :sigma-stream [(assoc sigma-expected :event/seq 1)]
         :counts {:alpha-pre-close 7
                  :alpha-final 8
                  :auto 2
                  :sigma 1
                   :delta 3
                  :bravo 1
                  :absent 0}
         :objects {:small {:digest small-digest
                           :bytes (utf-8-bytes object-small)
                           :encoding :utf-8}
                   :large {:digest large-digest
                           :bytes (utf-8-bytes object-large)
                           :encoding :utf-8}}
         :failures {:duplicate-event-id :journal-storage-failure
                    :count-after-duplicate 7
                    :events-after-unknown :journal-storage-failure
                    :non-string-object :journal-storage-failure
                    :malformed-digest ::fail-closed
                    :short-hex-digest ::fail-closed
                    :unknown-object-digest ::fail-closed}})

      (finally
        (store/close-store! store)))))

(deftest file-sqlite-differential-store-contract-test
  (let [observations (into {} (map (fn [backend]
                                     [backend (exercise-logical-contract! backend)])
                                   backends))]
    (testing "file and SQLite produce identical normalized logical observations"
      (is (= (get observations :file) (get observations :sqlite))))))

;; ---------------------------------------------------------------------------
;; Session-API differential flows (semantic recovery scenarios).
;; ---------------------------------------------------------------------------

(def ^:private session-prompt "store-contract differential system prompt")

(defn- assistant-stub []
  {:role :assistant :content nil})

(defn- project-agent-event
  "Projects a session-API event onto its backend-independent logical shape.
  Run-generated identities (event IDs, times, run IDs, request IDs,
  provider response IDs, bb4t event payloads) are excluded; caller-fixed
  values (session IDs, action IDs, sources, statuses, values) are kept."
  [event]
  (let [type (:event/type event)
        coordinate (:session/coordinate event)]
    (case type
      :session/started
      [:session/started (select-keys coordinate [:session/id :surface])
       (get-in coordinate [:model :provider])]

      :session/resumed
      [:session/resumed (select-keys coordinate [:session/id :surface])
       (get-in coordinate [:model :provider]) (:world/changed? event)]

      :session/checkpoint
      [:session/checkpoint (:checkpoint/reason event)
       (count (:session/messages event)) (count (:repl/replay-forms event))]

      :session/ended
      [:session/ended (:session/end-reason event)]

      :user/message
      [:user/message (:message/content event)]

      :repl/request
      [:repl/request (:action/id event) (:repl/source event)]

      :repl/result
      [:repl/result (:action/id event)
       (get-in event [:repl/result :status])
       (get-in event [:repl/result :evaluation :value :value/data])]

      :bb4t/event
      [:bb4t/event (get-in event [:bb4t/event :event/type])]

      [type])))

(defn- start-options [backend state-root session-id project]
  {:state-root state-root
   :project-root project
   :model-provider (provider/fake [])
   :system-prompt session-prompt
   :session-id session-id
   :store-backend backend})

(defn- resume-options [backend state-root session-id]
  {:state-root state-root
   :session-id session-id
   :model-provider (provider/fake [])
   :system-prompt session-prompt
   :store-backend backend})

(defn- exercise-session-resume!
  "Failed-form replay parity: start, evaluate one ok and one failed form,
  close, resume, and evaluate a form that depends on the failed form's
  partial effect."
  [backend project]
  (let [state-root (temp-root (str "bbagent-contract-resume-" (name backend)))
        session-id "contract-resume-a"
        first-session (session/start! (start-options backend state-root
                                                     session-id project))]
    (try
      (session/add-user-message! first-session "please compute")
      (let [ok (session/evaluate! first-session "act-ok" "(+ 40 2)"
                                  (assistant-stub))
            failed (session/evaluate! first-session "act-fail"
                                      "(do (def survived 41) (/ 1 0))"
                                      (assistant-stub))]
        (is (= :ok (:status ok)))
        (is (= 42 (get-in ok [:evaluation :value :value/data])))
        (is (= :error (:status failed))))
      (finally
        (session/close! first-session :restart-contract)))
    (let [resumed (session/resume! (resume-options backend state-root
                                                   session-id))]
      (try
        (is (= session-id (:session-id resumed)))
        (is (= session-id (get-in resumed [:coordinate :session/id])))
        (let [after (session/evaluate! resumed "act-after" "(+ survived 1)"
                                       (assistant-stub))
              value (get-in after [:evaluation :value :value/data])
              stream (mapv project-agent-event (session/session-events resumed))]
          (is (= 42 value) "failed-form replay restores the partial durable state")
          {:resumed-session-id (:session-id resumed)
           :replayed-value value
           :stream stream})
        (finally
          (session/close! resumed :contract-end))))))

(defn- exercise-durable-tail!
  "Durable-result-after-checkpoint parity: a request/result pair stored
  after the latest checkpoint must fold into the resumed session state."
  [backend project]
  (let [state-root (temp-root (str "bbagent-contract-tail-" (name backend)))
        session-id "contract-tail-b"
        first-session (session/start! (start-options backend state-root
                                                     session-id project))]
    (try
      (let [source "(def tail-value 9)"
            result (bb4t/evaluate (:bb4t first-session) source)]
        (store/append-event! (:store first-session) session-id
                             {:event/type :repl/request
                              :request/id "tail-req"
                              :action/id "tail-act"
                              :repl/source source})
        (store/append-event! (:store first-session) session-id
                             {:event/type :repl/result
                              :request/id "tail-req"
                              :action/id "tail-act"
                              :repl/result result}))
      (finally
        ;; Close without a trailing checkpoint so the pair stays in the
        ;; recovery tail (both unsubscribe and store close are idempotent).
        ((:unsubscribe first-session))
        (store/close-store! (:store first-session))))
    (let [resumed (session/resume! (resume-options backend state-root
                                                   session-id))]
      (try
        (let [after (session/evaluate! resumed "act-b" "(+ tail-value 1)"
                                       (assistant-stub))
              value (get-in after [:evaluation :value :value/data])
              stream (mapv project-agent-event (session/session-events resumed))]
          (is (= 10 value) "the durable tail result is folded on resume")
          {:folded-value value
           :stream stream})
        (finally
          (session/close! resumed :contract-end))))))

(defn- unresolved-request-outcome
  "Unresolved-request parity: a durable request without a result must fail
  recovery with one semantic category on both backends."
  [backend project]
  (let [state-root (temp-root (str "bbagent-contract-unresolved-" (name backend)))
        session-id "contract-unresolved-c"
        first-session (session/start! (start-options backend state-root
                                                     session-id project))]
    (try
      (store/append-event! (:store first-session) session-id
                           {:event/type :repl/request
                            :request/id "interrupted-req"
                            :action/id "interrupted-act"
                            :repl/source "(def interrupted 1)"})
      (finally
        ((:unsubscribe first-session))
        (store/close-store! (:store first-session))))
    (error-category #(session/resume! (resume-options backend state-root
                                                      session-id)))))

(deftest file-sqlite-session-resume-parity-test
  (let [project (fixture-project)
        observations (into {} (map (fn [backend]
                                     [backend (exercise-session-resume! backend
                                                                        project)])
                                   backends))]
    (testing "file and SQLite sessions normalize to identical logical streams"
      (is (= (get observations :file) (get observations :sqlite))))))

(deftest file-sqlite-recovery-edge-parity-test
  (let [project (fixture-project)
        tails (into {} (map (fn [backend]
                              [backend (exercise-durable-tail! backend project)])
                            backends))
        outcomes (into {} (map (fn [backend]
                                 [backend (unresolved-request-outcome backend
                                                                      project)])
                               backends))]
    (testing "durable tail results fold identically on both backends"
      (is (= (get tails :file) (get tails :sqlite))))
    (testing "unresolved tail requests fail recovery with one semantic category"
      (is (= :session-recovery-failure (get outcomes :file)))
      (is (= :session-recovery-failure (get outcomes :sqlite)))
      (is (= (get outcomes :file) (get outcomes :sqlite))))))
