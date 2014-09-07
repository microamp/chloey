(defproject chloey "0.1.0-SNAPSHOT"
  :description "A minimal IRC bot in Clojure"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.338.0-5c5012-alpha"]
                 [com.novemberain/monger "2.0.0"]]
  :plugins [[cider/cider-nrepl "0.8.0-SNAPSHOT"]]
  :main chloey.core)
