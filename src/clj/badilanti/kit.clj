(ns badilanti.kit
  (:require [badilanti.conf :as conf]
            [org.httpkit.server :refer [run-server]]))

(defonce stop-server (atom (fn [])))

(defn start-server [handler]
  (@stop-server)
  (reset! stop-server (run-server handler {:port (conf/server-port) :join? false})))
