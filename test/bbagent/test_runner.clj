(ns bbagent.test-runner
  (:require [bbagent.agent-test]
            [bbagent.coordinates-test]
            [bbagent.integration-test]
            [bbagent.journal-test]
            [bbagent.provider-test]
            [bbagent.sqlite-test]
            [clojure.test :as test])
  (:gen-class))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'bbagent.agent-test
                        'bbagent.coordinates-test
                        'bbagent.integration-test
                        'bbagent.journal-test
                        'bbagent.provider-test
                        'bbagent.sqlite-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
