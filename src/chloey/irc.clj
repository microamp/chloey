(ns chloey.irc
  (:import (java.net Socket)
           (java.io PrintWriter BufferedReader InputStreamReader)))

(defn connect [host port]
  (let [socket (Socket. host port)
        reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
        writer (PrintWriter. (.getOutputStream socket))]
    (ref {:reader reader
          :writer writer})))
