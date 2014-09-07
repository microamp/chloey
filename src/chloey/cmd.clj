(ns chloey.cmd)

(defn nick [nick]
  (str "NICK " nick))

(defn user [user]
  (str "USER " user " 0 * :" user))

(defn join [channel]
  (str "JOIN " channel))

(defn pong [server]
  (str "PONG :" server))

(defn quit []
  (str "QUIT :\"bye world\""))

(defn privmsg [tgt msg]
  (str "PRIVMSG " tgt " :" msg))
