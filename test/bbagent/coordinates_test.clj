(ns bbagent.coordinates-test
  (:require [bbagent.coordinates :as coordinates]
            [clojure.test :refer [deftest is testing]]))

(deftest deterministic-coordinate-test
  (testing "map insertion order is irrelevant"
    (is (= (coordinates/digest :test/value (array-map :a 1 :b 2))
           (coordinates/digest :test/value (array-map :b 2 :a 1)))))
  (testing "important configuration changes alter the coordinate"
    (is (not= (coordinates/digest :test/context
                                  {:profile :agent/minimal :grants #{}})
              (coordinates/digest :test/context
                                  {:profile :agent/project-read
                                   :grants #{:project/read}})))))

(deftest repository-credential-sanitization-test
  (let [sanitize @#'coordinates/safe-repository]
    (is (= "ssh://host/repo"
           (sanitize "ssh://user:password@host/repo?token=secret")))
    (is (= "https://example.test/repo"
           (sanitize "HTTPS://key@example.test/repo#secret")))
    (is (nil? (sanitize "https://user:password@例え.テスト/repo")))))
