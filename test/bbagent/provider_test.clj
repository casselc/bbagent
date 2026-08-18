(ns bbagent.provider-test
  (:require [bbagent.action :as action]
            [babashka.http-client :as http]
            [bbagent.errors :as errors]
            [bbagent.provider :as provider]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]))

(deftest openai-normalization-test
  (let [response (provider/normalize-openai-response
                  {:id "response-1"
                   :model "model-1"
                   :choices [{:finish_reason "tool_calls"
                              :message
                              {:tool_calls
                               [{:id "call-1"
                                 :function {:name "repl_eval"
                                            :arguments "{\"source\":\"(+ 1 2)\"}"}}]}}]}
                  7 "fallback")]
    (is (= {:action/type :repl/eval :source "(+ 1 2)"}
           (get-in response [:actions 0 :action/value])))
    (is (= 7 (:latency-ms response)))
    (is (nil? (:usage response)) "unknown usage must remain unknown")))

(deftest malformed-action-test
  (testing "invalid normalized action"
    (let [failure (try (action/normalize {:action/type :repl/eval :source ""})
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :agent-invalid-action (:bbagent/error (ex-data failure))))))
  (testing "malformed provider tool arguments"
    (let [failure
          (try
            (provider/normalize-openai-response
             {:choices [{:message {:tool_calls
                                   [{:id "bad"
                                     :function {:name "repl_eval"
                                                :arguments "not-json"}}]}}]}
             1 "model")
            nil
            (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :provider-malformed-response
             (:bbagent/error (ex-data failure))))))
  (testing "provider tool calls require their original ID"
    (let [failure
          (try
            (provider/normalize-openai-response
             {:choices [{:finish_reason "tool_calls"
                         :message {:tool_calls
                                   [{:function {:name "repl_eval"
                                                :arguments "{\"source\":\"(+ 1 2)\"}"}}]}}]}
             1 "model")
            nil
            (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :provider-malformed-response
             (:bbagent/error (ex-data failure)))))))

(deftest incomplete-text-response-test
  (doseq [finish-reason ["length" "content_filter" nil]]
    (let [failure
          (try
            (provider/normalize-openai-response
             {:choices [{:finish_reason finish-reason
                         :message {:content "partial answer"}}]}
             1 "model")
            nil
            (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :provider-malformed-response
             (:bbagent/error (ex-data failure)))))))

(deftest http-error-classification-test
  (let [client (provider/openai-compatible
                {:endpoint "https://example.test/v1/chat/completions"
                 :model "model" :api-key "not-journaled"})
        failure (with-redefs [http/post (fn [& _]
                                         {:status 401 :body "not-json"})]
                  (try (provider/complete client {:messages []})
                       nil
                       (catch clojure.lang.ExceptionInfo failure failure)))]
    (is (= :provider-failure (:bbagent/error (ex-data failure))))))

(deftest invalid-endpoint-classification-test
  (doseq [endpoint ["not a URI" "file:///tmp/provider" "https://key@example.test/x?q=secret"]]
    (let [failure (try
                    (provider/openai-compatible
                     {:endpoint endpoint :model "model" :api-key "key"})
                    nil
                    (catch clojure.lang.ExceptionInfo failure failure))]
      (is (= :provider-failure (:bbagent/error (ex-data failure)))))))

(deftest plaintext-http-restriction-test
  (doseq [endpoint ["http://localhost:11434/v1/chat/completions"
                    "http://127.0.0.1:8000/v1/chat/completions"
                    "http://127.0.0.2:8000/v1/chat/completions"
                    "http://[::1]:8000/v1/chat/completions"]]
    (is (satisfies? provider/ModelProvider
                    (provider/openai-compatible
                     {:endpoint endpoint :model "model" :api-key "key"}))))
  (let [failure (try
                  (provider/openai-compatible
                   {:endpoint "http://example.test/v1/chat/completions"
                    :model "model" :api-key "key"})
                  nil
                  (catch clojure.lang.ExceptionInfo failure failure))]
    (is (= :provider-failure (:bbagent/error (ex-data failure)))))
  (is (satisfies? provider/ModelProvider
                  (provider/openai-compatible
                   {:endpoint "http://example.test/v1/chat/completions"
                    :model "model"
                    :api-key "key"
                    :allow-insecure-http true}))))

(deftest encoded-tool-call-round-trip-test
  (let [requests (atom [])
        responses
        (atom [{:id "response-1"
                :model "model"
                :choices [{:finish_reason "tool_calls"
                           :message {:content nil
                                     :tool_calls
                                     [{:id "call-1"
                                       :type "function"
                                       :function
                                       {:name "repl_eval"
                                        :arguments "{\"source\":\"(+ 1 2)\"}"}}]}}]}
               {:id "response-2"
                :model "model"
                :choices [{:finish_reason "stop"
                           :message {:content "three"}}]}])
        client (provider/openai-compatible
                {:endpoint "https://example.test/v1/chat/completions"
                 :model "model" :api-key "not-journaled"})]
    (with-redefs [http/post
                  (fn [_ options]
                    (swap! requests conj (json/parse-string (:body options) true))
                    (let [response (first @responses)]
                      (swap! responses #(vec (rest %)))
                      {:status 200 :body (json/generate-string response)}))]
      (let [first-response
            (provider/complete client
                               {:messages [{:role :system :content "system"}
                                           {:role :user :content "calculate"}]})
            assistant (:message first-response)
            _ (provider/complete
               client
               {:messages [{:role :system :content "system"}
                           {:role :user :content "calculate"}
                           assistant
                           {:role :tool
                            :action/id "call-1"
                            :content "{:status :ok}"}]})
            second-messages (:messages (second @requests))]
        (is (false? (:parallel_tool_calls (first @requests))))
        (is (= "call-1" (get-in second-messages [2 :tool_calls 0 :id])))
        (is (= "call-1" (get-in second-messages [3 :tool_call_id])))))))

(deftest fake-provider-error-test
  (let [expected (errors/error :provider-failure "offline")
        fake (provider/fake [expected])]
    (is (identical? expected
                    (try (provider/complete fake {:messages []})
                         nil
                         (catch Throwable failure failure))))))
