(ns bbagent.test-runner
  (:require [bbagent.agent-test]
            [bbagent.coordinates-test]
            [bbagent.integration-test]
            [bbagent.journal-test]
             [bbagent.orientation-test]
             [bbagent.provider-test]
             [bbagent.replay-test]
             [bbagent.sqlite-store-test]
             [bbagent.sqlite-test]
             [bbagent.store-contract-test]
             [bbagent.tui-test]
             [clojure.test :as test])
  (:gen-class))

(defn -main [& _]
  (let [{:keys [fail error]}
        (test/run-tests 'bbagent.agent-test
                        'bbagent.coordinates-test
                        'bbagent.integration-test
                        'bbagent.journal-test
                        'bbagent.orientation-test
                        'bbagent.provider-test
                        'bbagent.replay-test
                        'bbagent.sqlite-store-test
                        'bbagent.sqlite-test
                        'bbagent.store-contract-test
                        'bbagent.tui-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
