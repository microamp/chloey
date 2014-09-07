(ns chloey.core
  (:import (java.net Socket)
           (java.io PrintWriter BufferedReader InputStreamReader))
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.async :as async :refer [chan go >! <! close!]]))

(def suffix "\r")

(defn cmd-nick [nick]
  (str "NICK " nick))

(defn cmd-user [user]
  (str "USER " user " 0 * :" user))

(defn cmd-join [channel]
  (str "JOIN " channel))

(defn cmd-pong [server]
  (str "PONG :" server))

(defn cmd-quit []
  (str "QUIT :\"bye world\""))

(defn cmd-privmsg [tgt msg]
  (str "PRIVMSG " tgt " :" msg))

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

(defn tokenise [s]
  (clojure.string/split s #"\s"))

(defn untokenise [tokens]
  (apply str (interpose " " tokens)))

(defn rm-colon [s]
  (apply str (rest (clojure.string/split s #":"))))

(defn pong [[_ _ server]]
  (cmd-pong (rm-colon server)))

(defn privmsg [[src cmd tgt & msg]]
  (if (and (= cmd "PRIVMSG")
           (.startsWith tgt "#"))
    (cmd-privmsg tgt
                 (rm-colon (untokenise msg)))))

(defn reply [conn msg]
  (let [tokens (tokenise msg)]
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
  (doseq [cmd [(cmd-nick nick)
               (cmd-user nick)
               (cmd-join channel)]]
    (write-buff conn cmd)))

(defn read-file [filename]
  ;; read edn file
  (-> filename
      io/resource
      slurp
      edn/read-string))

(defn trim-lower [s]
  ;; trim string and convert it to lower-case
  (-> s
      clojure.string/trim
      .toLowerCase))

(defn -main []
  (let [cfg (read-file "config.edn")
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
        (let [input (trim-lower (read-line))]
          (if (= input "q")
            (do
              (write-buff conn (cmd-quit))
              (close! ch))
            (recur)))))))
