(ns bb-test-runner
  (:require
   [clojure.test :as t]
   [unit.rct-test]))

(defn run-tests [& _args]
  (let [{:keys [fail error]}
        (t/run-tests
         'unit.rct-test)]
    (when (or (pos? fail)
              (pos? error))
      (System/exit 1))))
