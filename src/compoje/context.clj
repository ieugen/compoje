(ns compoje.context
  "Functions to manage context"
  (:require [clojure.tools.logging :as log]
            [compoje.utils :as u]))

(defn load-context!
  "Builds context as a map of values and returns it.
   Merges in values from cli to replace values from files.

   Return a map."
  [template-dir config secrets]
  (let [cwd (System/getProperty "user.dir")
        values (:values config)
        values (u/deep-merge {} values secrets)
        context {:config config
                 :values values
                 :secrets secrets
                 :work-dir cwd
                 :template-dir template-dir
                 :output "stack.generated.yml"}]
    context))

(comment

  (load-context! "data/stacks/nginx" {} {})

  (u/deep-merge {} {:a "b"})
  (u/deep-merge {} {:a "b"} {:c "d"})

  )

