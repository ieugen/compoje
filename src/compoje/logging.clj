(ns compoje.logging
  "Output"
  (:require [clojure.string :as str]))

(defn default-output-msg-fn
  "(fn [data]) -> string, used by `default-output-fn` to generate output
  for `:vargs` value (vector of raw logging arguments) in log data."
  [{:keys [msg-type ?msg-fmt vargs] :as _data}]

  (case msg-type
    nil ""
    :p  (str/join " " vargs)
    :f
    (if (string?   ?msg-fmt)
      (format ?msg-fmt vargs) ; Don't use arg->str-fn, would prevent custom formatting
      (throw
       (ex-info "Timbre format-style logging call without a format pattern string"
                {:?msg-fmt ?msg-fmt :type (type ?msg-fmt) :vargs vargs})))))

(defn output-fn
  [data]
  (let [{:keys [output-opts]}
        data]
    (str
     (when-let [msg-fn (get output-opts :msg-fn default-output-msg-fn)]
       (msg-fn data)))))

(comment
  ;; use our clean log formatting fn
  (taoensso.timbre/merge-config! {:output-fn output-fn})
  )