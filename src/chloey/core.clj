(ns chloey.core
  (:import (java.net Socket)
           (java.io PrintWriter BufferedReader InputStreamReader))
  (:require [clojure.core.async :as async :refer [chan go >! <! close!]]
            [chloey.cmd :as cmd]
            [chloey.common :as common]
            [chloey.io :as io]))

(def suffix "\r")

(defn read-buff [conn]
  (let [reader (:reader @conn)
        msg (.readLine reader)]
    (do
      (prn (str "reading: " msg))
      msg)))

(defn write-buff [conn msg]
  (if (not (nil? msg))
    (do
      (prn (str "writing: " msg))
      (doto (:writer @conn)
        (.println (str msg suffix))
        (.flush)))))

(defn pong [[_ _ server]]
  (cmd/pong (common/rm-colon server)))

(defn privmsg [[src cmd tgt & msg]]
  (if (and (= cmd "PRIVMSG")
           (.startsWith tgt "#"))
    (cmd/privmsg tgt
                 (common/rm-colon (common/untokenise msg)))))

(defn reply [conn msg]
  (let [tokens (common/tokenise msg)]
    (write-buff conn
                (cond
                 (= (first tokens) "PING")
                 (pong (cons "" tokens))
                 (= (second tokens) "PING")
                 (pong tokens)
                 (= (second tokens) "PRIVMSG")
                 (privmsg tokens)))))

(defn connect [server port]
  (let [socket (Socket. server port)
        reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
        writer (PrintWriter. (.getOutputStream socket))]
    (ref {:reader reader
          :writer writer})))

(defn login [conn nick channel]
  (doseq [cmd [(cmd/nick nick)
               (cmd/user nick)
               (cmd/join channel)]]
    (write-buff conn cmd)))

(defn -main []
  (let [cfg (io/read-file "config.edn")
        conn (connect (get-in cfg [:conn-info :server])
                      (get-in cfg [:conn-info :port]))]
    ;; log in and join channel
    (login conn
           (get-in cfg [:conn-info :nick])
           (get-in cfg [:conn-info :channel]))
    ;; set up csp channel
    (let [ch (chan)]
      ;; (single) producer
      (go (loop []
            (let [msg (read-buff conn)]
              (if (not (nil? msg))
                (>! ch msg)))
            (recur)))
      ;; (multiple) consumers
      (doseq [_ (range (:consumers cfg))]
        (go (loop []
              (let [msg (<! ch)]
                (if (not (nil? msg))
                  (reply conn msg)))
              (recur))))
      ;; 'q' to quit
      (loop []
        (let [input (common/trim-lower (read-line))]
          (if (= input "q")
            (do
              (write-buff conn (cmd/quit))
              (close! ch))
            (recur)))))))
