(ns chloey.common)

(defn tokenise [s]
  (clojure.string/split s #"\s"))

(defn untokenise [tokens]
  (apply str (interpose " " tokens)))

(defn rm-prefix [s prefix]
  (if (.startsWith s prefix)
    (apply str (drop (count prefix) s))
    s))

(defn trim-lower [s]
  (-> s
      clojure.string/trim
      .toLowerCase))
