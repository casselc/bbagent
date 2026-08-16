(ns bbagent.provider
  (:require [babashka.http-client :as http]
            [bbagent.action :as action]
            [bbagent.errors :as errors]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.time Duration Instant]
           [java.util UUID]))

(defprotocol ModelProvider
  (describe-provider [provider])
  (complete [provider request]))

(defn- elapsed-ms [^Instant started]
  (.toMillis (Duration/between started (Instant/now))))

(defn- parse-arguments [arguments]
  (try
    (cond
      (string? arguments) (json/parse-string arguments true)
      (map? arguments) arguments
      :else (throw (ex-info "Tool arguments are not an object" {})))
    (catch Throwable failure
      (throw (errors/error :provider-malformed-response
                           "Provider returned malformed tool arguments"
                           {:arguments/type (some-> arguments class .getName)}
                           failure)))))

(defn- tool-action [tool-call]
  (try
    (let [function (:function tool-call)
          arguments (parse-arguments (:arguments function))
          candidate (case (:name function)
                      "repl_eval" {:action/type :repl/eval
                                   :source (:source arguments)}
                      "finish" {:action/type :finish
                                :message (:message arguments)}
                      (throw (ex-info "Unknown provider tool" {})))]
      {:action/id (or (:id tool-call) (str (UUID/randomUUID)))
       :action/value (action/normalize candidate)})
    (catch clojure.lang.ExceptionInfo failure
      (if (= :provider-malformed-response
             (:bbagent/error (ex-data failure)))
        (throw failure)
        (throw (errors/error :provider-malformed-response
                             "Provider returned an invalid tool action"
                             {:tool/name (get-in tool-call [:function :name])}
                             failure))))))

(defn normalize-openai-response [body latency-ms fallback-model]
  (try
    (let [choice (first (:choices body))
          message (:message choice)
          tool-calls (:tool_calls message)
          content (:content message)
          actions (if (seq tool-calls)
                    (mapv tool-action tool-calls)
                    (when (string? content)
                      [{:action/id (str (UUID/randomUUID))
                        :action/value (action/normalize
                                       {:action/type :finish
                                        :message content})}]))]
      (when-not (and choice (seq actions))
        (throw (errors/error :provider-malformed-response
                             "Provider response has no usable action"
                             {:response/id (:id body)})))
      {:provider :openai-compatible
       :model (or (:model body) fallback-model)
       :response/id (:id body)
       :created (:created body)
       :usage (when (contains? body :usage) (:usage body))
       :finish-reason (:finish_reason choice)
       :latency-ms latency-ms
       :message {:role :assistant
                 :content content
                 :actions actions}
       :actions actions})
    (catch clojure.lang.ExceptionInfo failure
      (if (:bbagent/error (ex-data failure))
        (throw failure)
        (throw (errors/error :provider-malformed-response
                             "Could not normalize provider response" nil failure))))
    (catch Throwable failure
      (throw (errors/error :provider-malformed-response
                           "Could not normalize provider response" nil failure)))))

(defn- openai-tool [name description properties required]
  {:type "function"
   :function {:name name
              :description description
              :parameters {:type "object"
                           :additionalProperties false
                           :properties properties
                           :required required}}})

(def ^:private tools
  [(openai-tool "repl_eval"
                "Evaluate Clojure source in the persistent bounded SCI context."
                {:source {:type "string"}}
                ["source"])
   (openai-tool "finish" "Return the final answer to the human."
                {:message {:type "string"}}
                ["message"])])

(defn- encode-message [{:keys [role content actions action/id]}]
  (case role
    :assistant
    (cond-> {:role "assistant" :content content}
      (seq actions)
      (assoc :tool_calls
             (mapv (fn [{:action/keys [id value]}]
                     {:id id
                      :type "function"
                      :function
                      (case (:action/type value)
                        :repl/eval {:name "repl_eval"
                                    :arguments (json/generate-string
                                                {:source (:source value)})}
                        :finish {:name "finish"
                                 :arguments (json/generate-string
                                             {:message (:message value)})})})
                   actions)))

    :tool {:role "tool" :tool_call_id id :content content}
    {:role (name role) :content content}))

(defrecord OpenAICompatibleProvider [endpoint model api-key timeout-ms reasoning-effort]
  ModelProvider
  (describe-provider [_]
    {:provider :openai-compatible
     :endpoint endpoint
     :model model
     :reasoning-effort reasoning-effort})
  (complete [_ request]
    (let [started (Instant/now)
          request-body
          (cond-> {:model model
                   :messages (mapv encode-message (:messages request))
                   :tools tools
                   :tool_choice "auto"}
            reasoning-effort (assoc :reasoning_effort reasoning-effort))]
      (try
        (let [response (http/post endpoint
                                  {:headers {"content-type" "application/json"}
                                   :oauth-token api-key
                                   :body (json/generate-string request-body)
                                   :timeout timeout-ms
                                   :throw false})
              status (:status response)
              successful? (<= 200 status 299)]
          (when-not successful?
            (throw (errors/error :provider-failure "Provider request failed"
                                 {:status status})))
          (let [body (try
                       (json/parse-string (:body response) true)
                       (catch Throwable failure
                         (throw (errors/error
                                 :provider-malformed-response
                                 "Provider returned non-JSON response"
                                 {:status status} failure))))]
            (normalize-openai-response body (elapsed-ms started) model)))
        (catch clojure.lang.ExceptionInfo failure
          (if (:bbagent/error (ex-data failure))
            (throw failure)
            (throw (errors/error :provider-failure "Provider request failed"
                                 {:error/type (.getName (class failure))} failure))))
        (catch Throwable failure
          (throw (errors/error :provider-failure "Provider request failed"
                               {:error/type (.getName (class failure))} failure)))))))

(defn openai-compatible
  [{:keys [endpoint model api-key timeout-ms reasoning-effort]
    :or {timeout-ms 120000}}]
  (when (or (str/blank? endpoint) (str/blank? model) (str/blank? api-key))
    (throw (errors/error :provider-failure
                         "Provider endpoint, model, and API key are required")))
  (let [uri (try
              (java.net.URI/create endpoint)
              (catch Throwable failure
                (throw (errors/error :provider-failure
                                     "Provider endpoint is invalid" nil failure))))]
    (when (or (not (#{"http" "https"} (.getScheme uri)))
              (str/blank? (.getHost uri))
              (.getUserInfo uri) (.getQuery uri) (.getFragment uri))
      (throw (errors/error :provider-failure
                           "Provider endpoint must be an HTTP(S) URL without credentials, query, or fragment"))))
  (->OpenAICompatibleProvider endpoint model api-key timeout-ms reasoning-effort))

(defrecord FakeProvider [responses description]
  ModelProvider
  (describe-provider [_] description)
  (complete [_ request]
    (let [response (first @responses)]
      (swap! responses #(vec (rest %)))
      (cond
        (nil? response) (throw (errors/error :provider-failure
                                             "Fake provider exhausted"))
        (instance? Throwable response) (throw response)
        (fn? response) (response request)
        :else response))))

(defn fake
  ([responses] (fake responses {:provider :fake :model "deterministic"}))
  ([responses description]
   (->FakeProvider (atom (vec responses)) description)))

(defn fake-response [action-value]
  (let [normalized (action/normalize action-value)
        action-id (str (UUID/randomUUID))]
    {:provider :fake
     :model "deterministic"
     :response/id (str (UUID/randomUUID))
     :usage nil
     :finish-reason (if (= :finish (:action/type normalized)) :stop :tool-call)
     :latency-ms 0
     :message {:role :assistant
               :content nil
               :actions [{:action/id action-id :action/value normalized}]}
     :actions [{:action/id action-id :action/value normalized}]}))
