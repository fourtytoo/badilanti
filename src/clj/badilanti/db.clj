(ns badilanti.db
  (:require [clojurewerkz.elastisch.rest :as esr]
            [clojurewerkz.elastisch.rest.document :as esd]
            [clojurewerkz.elastisch.rest.index :as esi]
            [clojurewerkz.elastisch.rest.response :as esrsp]
            [clojurewerkz.elastisch.query :as q]
            [onelog.core :as log]
            [clj-time.core :as time]
            [badilanti.conf :as conf]))

(def default-boards #{"gulp" "freelance"})

(cheshire.generate/add-encoder
 org.joda.time.DateTime
 (fn [c jg]
   (cheshire.generate/encode-long (clj-time.coerce/to-long c) jg)))

(defn all-boards []
  (or (conf/conf :elasticsearch :boards)
      default-boards))

(defn valid-board? [board]
  (boolean ((all-boards) board)))

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

(defn clean-profile-result [result]
  (when (:found result)
    (let [{:keys [_id _type _source]} result]
      (merge {:id _id :board _type} _source))))

(defn clean-query-result [result]
  (if (esrsp/any-hits? result)
    [true (map (fn [{:keys [_id _score _type _source]}]
                 (merge {:id _id :score _score :board _type} _source))
               (esrsp/hits-from result))]
    [false result]))

(defn get-profile [board id]
  (assert (valid-board? board))
  (-> (esd/get @conn (index) board (str id))
      clean-profile-result))

#_(get-profile "freelance" "bar")

(defn delete-profile [board id]
  (assert (valid-board? board))
  (let [res (esd/delete @conn (index) board (str id))]
    [(:found res) res]))

#_(delete-profile "gulp" "bar")

(defn search-profiles
  ([query]
   (search-profiles (seq (all-boards)) query))
  ([board query]
   (if (seq? board)
     (assert (every? valid-board? board))
     (assert (valid-board? board)))
   (-> (esd/search @conn (index) board
                   :query {:bool
                           {:must {:match {:skills query}}
                            :should {:match {:projects query}}}})
       clean-query-result)))

#_(search-profiles "perl cobol")

(defn list-profiles
  ([]
   (list-profiles (all-boards)))
  ([board]
   (if (seq? board)
     (assert (every? valid-board? board))
     (assert (valid-board? board)))
   (-> (esd/search @conn (index) board)
       clean-query-result)))

#_(esrsp/total-hits (list-profiles "gulp"))
(list-profiles "gulp")

(defn drop-database []
  (esi/delete @conn))

(defmulti normalise-profile
  "Convert a profile to the format used in the database."
  :board)


