(ns chloey.common)

(defn trim-lower [s]
  (-> s
      clojure.string/trim
      .toLowerCase))
