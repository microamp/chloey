(ns chloey.core
  (:import (java.net Socket)
           (java.io PrintWriter BufferedReader InputStreamReader))
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.async :as async :refer :all]))

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
    (do
      (prn (str "reading: " msg))
      msg)))

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
  (do
    (write conn (cmd-nick nick))
    (write conn (cmd-user nick))
    (write conn (cmd-join channel))))

(defn connect [server port]
  (let [socket (Socket. server port)
        reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
        writer (PrintWriter. (.getOutputStream socket))]
    (ref {:reader reader
          :writer writer})))

(defn read-file [filename]
  ; read edn file
  (->> filename
       io/resource
       slurp
       edn/read-string))

(defn -main []
  (let [conn-info (read-file "conn-info.edn")]
    (let [conn (connect (:server conn-info) (:port conn-info))]
      ; log in and join channel
      (login conn (:nick conn-info) (:channel conn-info))
      ; set up csp channel
      (let [ch (chan)]
        (go (while true
              (>! ch (read conn))))
        (go (while true
              (reply conn (<! ch))))
        ; TODO: (close! ch) if 'q' otherwise send privmsg
        (prn (read-line))))))
