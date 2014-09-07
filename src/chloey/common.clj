(ns chloey.common)

(defn tokenise [s]
  (clojure.string/split s #"\s"))

(defn untokenise [tokens]
  (apply str (interpose " " tokens)))

(defn rm-colon [s]
  (apply str (rest (clojure.string/split s #":"))))

(defn trim-lower [s]
  (-> s
      clojure.string/trim
      .toLowerCase))
