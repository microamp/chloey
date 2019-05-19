(ns chloey.io
  (:import [org.bson.types ObjectId])
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [monger.core :as mg]
            [monger.collection :as mc]))

(def coll-factoid "factoid")

(defn read-file [filename]
  (-> filename
      io/resource
      slurp
      edn/read-string))

(defn connect-db [{host :host port :port}]
  (mg/connect {:host host
               :port port}))

(defn get-db [conn db]
  (mg/get-db conn db))

(defn disconnect-db [conn]
  (mg/disconnect conn))

(defn upsert-doc [db {reporter :reporter
                      subject :subject
                      factoid :factoid
                      type :type}]
  (mc/update db
             coll-factoid
             {:subject subject}
             {:reporter reporter
              :subject subject
              :factoid factoid
              :type type}
             {:upsert true}))

(defn read-doc [db subject]
  (mc/find-one-as-map db
                      coll-factoid
                      {:subject subject}))
