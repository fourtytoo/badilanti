(ns badilanti.conf
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [environ.core :refer [env]]
            [onelog.core :as log]))

(def ^:dynamic *default-port* 10555)

(defn load-configuration [fname]
  (if-let [rsc (io/resource fname)]
    (with-open [rdr (-> rsc
                      io/reader
                      java.io.PushbackReader.)]
      (edn/read rdr))
    (do
      (log/warn "No configuration file found (" fname "), defaulting to none.")
      {})))

(def configuration (atom (load-configuration "config.edn")))

(defn conf [& keys]
  (get-in @configuration keys))

(defn configure! [keys value]
  (swap! configuration #(assoc-in % keys value)))

(defn server-port []
  (Integer. (or (env :port)
                (conf :port)
                *default-port*)))

