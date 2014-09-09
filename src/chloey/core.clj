(ns chloey.core
  (:require [clojure.core.async :as async :refer [chan go >! <! close!]]
            [chloey.cmd :as cmd]
            [chloey.common :as common]
            [chloey.io :as io]
            [chloey.irc :as irc]))

(def cfg-file "config.edn")
(def cfg (io/read-file cfg-file))
(def db-conn (io/connect-db (:db cfg)))

(defn get-db [conn]
  (io/get-db db-conn (get-in cfg [:db :db])))

(defn ping? [[cmd]]
  (= cmd "PING"))

(defn privmsg? [[_ cmd]]
  (= cmd "PRIVMSG"))

(defn colon-separated [msg]
  (clojure.string/split msg #":"))

(defn is-separated [msg]
  (clojure.string/split msg #"\sis\s"))

(defn build-msg [msgs]
  (apply str (interpose " " msgs)))

(defn factoid? [[_ _ _ & msgs]]
  (and (= (first msgs)
          (str ":" (get-in cfg [:irc :nick]) ":"))
       (some #(= % "is") msgs)))

(defn question? [[_ _ _ & msgs]]
  (.endsWith (last msgs)
             "?"))

(defn get-nick [src]
  ;; e.g. :microamp!~user@localhost.localdomainN -> microamp
  (-> src
      (clojure.string/split #":")
      second
      (clojure.string/split #"!~")
      first))

(defn get-subject [msgs]
  (apply str (interpose " " (take-while #(not= % "is") (rest msgs)))))

(defn get-factoid [msgs]
  (apply str (interpose " " (rest (drop-while #(not= % "is") msgs)))))

(defn pong [[_ server]]
  (cmd/pong (second (colon-separated server))))

(defn store-factoid [[src _ tgt & msgs]]
  (if (.startsWith tgt "#")
    (let [reporter (get-nick src)
          subject (get-subject msgs)
          factoid (get-factoid msgs)]
      (if (and (not (empty? subject))
               (not (empty? factoid)))
        (do
          (io/upsert-doc (get-db db-conn)
                         {:reporter reporter
                          :subject subject
                          :factoid factoid})
          nil)))))

(defn retrieve-factoid [[_ cmd tgt & msgs]]
  (if (.startsWith tgt "#")
    (let [msg (build-msg msgs)
          doc (io/read-doc (get-db db-conn)
                           (apply str (-> msg rest butlast)))]
      (if (not (nil? doc))
        (cmd/privmsg tgt (str (:reporter doc)
                              " said "
                              (:subject doc)
                              " is "
                              (:factoid doc)))))))

(defn reply [irc-conn msg]
  (let [tokens (clojure.string/split msg #"\s")]
    (irc/write irc-conn
               (cond
                ;; ping
                (ping? tokens)
                (pong tokens)
                ;; store factoid
                (and (privmsg? tokens)
                     (factoid? tokens))
                (store-factoid tokens)
                ;; retrieve factoid
                (and (privmsg? tokens)
                     (question? tokens))
                (retrieve-factoid tokens)))))

(defn login [irc-conn nick channel]
  (doseq [cmd [(cmd/nick nick)
               (cmd/user nick)
               (cmd/join channel)]]
    (irc/write irc-conn cmd)))

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
            (let [msg (irc/read irc-conn)]
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
              (irc/write irc-conn (cmd/quit))
              (close! ch)
              (io/disconnect-db db-conn))
            (recur)))))))
