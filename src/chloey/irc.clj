(ns chloey.irc
  (:import (java.net Socket)
           (java.io PrintWriter BufferedReader InputStreamReader)))

(def suffix "\r")

(defn read [irc-conn]
  (let [reader (:reader @irc-conn)
        msg (.readLine reader)]
    (do
      (prn (str "reading: " msg))
      msg)))

(defn write [irc-conn msg]
  (if (not (nil? msg))
    (do
      (prn (str "writing: " msg))
      (doto (:writer @irc-conn)
        (.println (str msg suffix))
        (.flush)))))

(defn connect [host port]
  (let [socket (Socket. host port)
        reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
        writer (PrintWriter. (.getOutputStream socket))]
    (ref {:reader reader
          :writer writer})))
