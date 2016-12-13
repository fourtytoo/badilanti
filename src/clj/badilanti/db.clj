(ns badilanti.db
  (:require [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.index :as esi]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.query :as q]
            [badilanti.conf :as conf]))

(def default-boards #{"gulp" "freelance"})

(defn all-boards []
  (or (conf/conf :elasticsearch :boards)
      default-boards))

(defn valid-board? [board]
  ((all-boards) board))

(defn connect []
  (esr/connect (or (conf/conf :elasticsearch :url)
                   "http://127.0.0.1:9200")
               {:basic-auth (conf/conf :elasticsearch :auth)}))

(def conn (delay (connect)))

(def index (memoize #(or (conf/conf :elasticsearch :index)
                         "badilanti")))

#_(defn- create-indeces []
  (esi/create @conn (index)
              {"profile" {:properties
                          {:board {:type "string"
                                   :index "not_analyzed"}}}}))

(defn put-profile [board id data]
  (esd/put @conn (index) board id data))

#_(put-profile "gulp" "foo" {:raw-data "bar"})
#_(put-profile "gulp" "bar" {:raw-data "foo"})
#_(put-profile "freelance" "bar" {:raw-data "foobar"})

(defn get-profile [board id]
  (assert (valid-board? board))
  (esd/get @conn (index) board id))

#_(get-profile "freelance" "bar")

(defn delete-profile [board id]
  (assert (valid-board? board))
  (esd/delete @conn (index) board id))

#_(delete-profile "gulp" "bar")

(defn search-profiles
  ([query]
   (esd/search @conn (index) (all-boards) :query query))
  ([board query]
   (assert (valid-board? board))
   (esd/search @conn (index) board :query query)))

(defn list-profiles
  ([]
   (esd/search @conn (index) (all-boards)))
  ([board]
   (assert (valid-board? board))
   (esd/search @conn (index) board)))

#_(list-profiles "gulp")

(defn drop-database []
  (esi/delete @conn))

