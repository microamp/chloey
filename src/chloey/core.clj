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

(defn pong [[_ server]]
  (cmd/pong (second (colon-separated server))))

(defn build-msg [msgs]
  (apply str (interpose " " msgs)))

(defn reply? [[_ _ _ & msgs]]
  (and (= (first msgs)
          (str ":" (get-in cfg [:irc :nick]) ":"))
       ((complement nil?) (re-find #".+\sis\s<reply>.+"
                                   (build-msg (rest msgs))))))

(defn factoid? [[_ _ _ & msgs]]
  (and (= (first msgs)
          (str ":" (get-in cfg [:irc :nick]) ":"))
       ((complement nil?) (re-find #".+\sis\s.+"
                                   (build-msg (rest msgs))))))

(defn question? [[_ _ _ & msgs]]
  (.endsWith (last msgs) "?"))

(defn get-nick [src]
  ;; e.g. :microamp!~user@localhost.localdomain -> microamp
  (-> src
      (clojure.string/split #":")
      second
      (clojure.string/split #"!~")
      first))

(defn get-reply-pairs [msgs]
  (let [split (clojure.string/split (build-msg (rest msgs))
                                    #"\sis\s<reply>")]
    [(first split) (build-msg (rest split))]))

(defn get-factoid-pairs [msgs reply]
  (let [split (if reply
                (clojure.string/split (build-msg (rest msgs))
                                      #"\sis\s<reply>")
                (clojure.string/split (build-msg (rest msgs))
                                      #"\sis\s"))]
    [(first split) (build-msg (rest split))]))

(defn store-reply [[src _ tgt & msgs]]
  (if (.startsWith tgt "#")
    (let [reporter (get-nick src)
          factoid-pairs (get-factoid-pairs msgs true)
          subject (get factoid-pairs 0)
          factoid (get factoid-pairs 1)
          type "reply"]
      (if (and (not (empty? subject))
               (not (empty? factoid)))
        (do
          (io/upsert-doc (get-db db-conn)
                         {:reporter reporter
                          :subject subject
                          :factoid factoid
                          :type type})
          nil)))))

(defn store-factoid [[src _ tgt & msgs]]
  (if (.startsWith tgt "#")
    (let [reporter (get-nick src)
          factoid-pairs (get-factoid-pairs msgs false)
          subject (get factoid-pairs 0)
          factoid (get factoid-pairs 1)
          type "factoid"]
      (if (and (not (empty? subject))
               (not (empty? factoid)))
        (do
          (io/upsert-doc (get-db db-conn)
                         {:reporter reporter
                          :subject subject
                          :factoid factoid
                          :type type})
          nil)))))

(defn retrieve-reply [[_ cmd tgt & msgs]]
  (if (.startsWith tgt "#")
    (let [msg (build-msg msgs)
          type "reply"
          doc (io/read-doc (get-db db-conn)
                           (apply str (rest msg))
                           type)]
      (if (not (nil? doc))
        (cmd/privmsg tgt (:factoid doc))))))

(defn retrieve-factoid [[_ cmd tgt & msgs]]
  (if (.startsWith tgt "#")
    (let [msg (build-msg msgs)
          type "factoid"
          doc (io/read-doc (get-db db-conn)
                           (apply str (-> msg rest butlast))
                           type)]
      (if (not (nil? doc))
        (cmd/privmsg tgt (str (:reporter doc)
                              " said "
                              (:subject doc)
                              " is "
                              (:factoid doc)))))))

(defn reply [irc-conn msg]
  (let [tokens (filter (complement empty?)
                       (clojure.string/split msg #"\s"))]
    (irc/write irc-conn
               (cond
                ;; ping
                (ping? tokens)
                (pong tokens)
                ;; store reply
                (and (privmsg? tokens)
                     (reply? tokens))
                (store-reply tokens)
                ;; store factoid
                (and (privmsg? tokens)
                     (factoid? tokens))
                (store-factoid tokens)
                ;; retrieve reply
                (and (privmsg? tokens)
                     ((complement question?) tokens))
                (retrieve-reply tokens)
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
