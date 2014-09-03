(ns chloey.core
  (:import (java.net Socket)
           (java.io PrintWriter BufferedReader InputStreamReader))
  (:require [clojure.core.async :as async :refer :all]))

(def suffix "\r")

(defn cmd-nick [nick]
  (str "NICK " nick))

(defn cmd-user [user]
  (str "USER " user " 0 * :" user))

(defn cmd-join [channel]
  (str "JOIN " channel))

(defn cmd-pong [server]
  (str "PONG :" server))

(defn cmd-privmsg [msg]
  ; TODO
  "")

(defn read [conn]
  (let [reader (:reader @conn)
        msg (.readLine reader)]
    (prn (str "reading: " msg))
    msg))

(defn write [conn msg]
  (do
    (prn (str "writing: " msg))
    (doto (:writer @conn)
      (.println (str msg suffix))
      (.flush))))

(defn reply [conn msg]
  ; TODO: reply privmsg
  (cond
   (re-find #"^PING" msg)
   (go (write conn
              (cmd-pong (apply str (rest (re-find #":.*" msg))))))))

(defn login [conn nick channel]
  (write conn (cmd-nick nick))
  (write conn (cmd-user nick))
  (write conn (cmd-join channel)))

(defn connect [server port]
  (let [socket (Socket. server port)
        reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
        writer (PrintWriter. (.getOutputStream socket))]
    (ref {:reader reader
          :writer writer})))

(defn -main []
  (let [server "chat.freenode.net"
        port 8002
        nick "chloey"
        channel "#microamp"]
    (let [conn (connect server port)]
      ; log in and join channel
      (login conn nick channel)
      ; set up csp channel
      (let [ch (chan)]
        (go (while true
              (>! ch (read conn))))
        (go (while true
              (reply conn (<! ch))))
        ; TODO: (close! ch) if 'q' otherwise send privmsg
        (prn (read-line))))))
