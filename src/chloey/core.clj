(ns chloey.core
  (:require [clojure.core.async :as async :refer [chan go >! <! close!]]
            [chloey.cmd :as cmd]
            [chloey.common :as common]
            [chloey.irc :as irc]
            [chloey.io :as io]))

(def suffix "\r")

(def cfg (io/read-file "config.edn"))

(def db-conn (io/connect-db (:db cfg)))

(defn read-buff [irc-conn]
  (let [reader (:reader @irc-conn)
        msg (.readLine reader)]
    (do
      (prn (str "reading: " msg))
      msg)))

(defn write-buff [irc-conn msg]
  (if (not (nil? msg))
    (do
      (prn (str "writing: " msg))
      (doto (:writer @irc-conn)
        (.println (str msg suffix))
        (.flush)))))

(defn get-db [conn]
  (io/get-db db-conn (get-in cfg [:db :db])))

(defn factoid? [[_ _ _ & msgs]]
  (let [msg (common/untokenise msgs)]
    (and (.startsWith msg (str ":" (get-in cfg [:irc :nick]) ": "))
         ((complement nil?) (re-find #"\s.+\sis\s.+" msg)))))

(defn question? [[_ _ _ & msgs]]
  (.endsWith (common/untokenise msgs) "?")
  (let [msg (common/untokenise msgs)]
    (.endsWith msg "?")))

(defn get-nick [src]
  ;; :microamp!~user@localhost.localdomain -> microamp
  (common/rm-prefix (first (clojure.string/split src #"!~"))
                    ":"))

(defn pong [[_ _ server]]
  (cmd/pong (common/rm-prefix server ":")))

(defn store-factoid [[src cmd tgt & msgs]]
  (if (and (= cmd "PRIVMSG")
           (.startsWith tgt "#"))
    (let [msg (common/untokenise msgs)
          tokens (clojure.string/split msg #"\sis\s")
          subject (common/rm-prefix (first tokens)
                                    (str ":" (get-in cfg [:irc :nick]) ": "))
          factoid (apply str (interpose " is " (rest tokens)))]
      (do
        (io/upsert-doc (get-db db-conn)
                       {:reporter (get-nick src)
                        :subject subject
                        :factoid factoid})
        nil))))

(defn retrieve-factoid [[_ cmd tgt & msgs]]
  (if (and (= cmd "PRIVMSG")
           (.startsWith tgt "#"))
    (let [msg (common/untokenise msgs)
          doc (io/read-doc (get-db db-conn)
                           (apply str (-> msg rest butlast)))]
      (if (not (nil? doc))
        (do
          (cmd/privmsg tgt (:factoid doc)))))))

(defn reply [irc-conn msg]
  (let [tokens (common/tokenise msg)]
    (write-buff irc-conn
                (cond
                 ;; ping
                 (= (first tokens) "PING")
                 (pong (cons "" tokens))
                 ;; ping
                 (= (second tokens) "PING")
                 (pong tokens)
                 ;; store factoid
                 (and (= (second tokens) "PRIVMSG")
                      (factoid? tokens))
                 (store-factoid tokens)
                 ;; retrieve factoid
                 (and (= (second tokens) "PRIVMSG")
                      (question? tokens))
                 (retrieve-factoid tokens)))))

(defn login [irc-conn nick channel]
  (doseq [cmd [(cmd/nick nick)
               (cmd/user nick)
               (cmd/join channel)]]
    (write-buff irc-conn cmd)))

(defn -main []
  (let [irc-conn (irc/connect (get-in cfg [:irc :host])
                              (get-in cfg [:irc :port]))]
    ;; log in and join channel
    (login irc-conn
           (get-in cfg [:irc :nick])
           (get-in cfg [:irc :channel]))
    ;; set up csp channel
    (let [ch (chan)]
      ;; (single) producer
      (go (loop []
            (let [msg (read-buff irc-conn)]
              (if (not (nil? msg))
                (>! ch msg)))
            (recur)))
      ;; (multiple) consumers
      (doseq [_ (range (get-in cfg [:csp :consumers]))]
        (go (loop []
              (let [msg (<! ch)]
                (if (not (nil? msg))
                  (reply irc-conn msg)))
              (recur))))
      ;; 'q' to quit
      (loop []
        (let [input (common/trim-lower (read-line))]
          (if (= input "q")
            (do
              (write-buff irc-conn (cmd/quit))
              (close! ch)
              (io/disconnect-db db-conn))
            (recur)))))))
