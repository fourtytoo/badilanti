(ns badilanti.jetty
  (:require [badilanti.conf :as conf]
            [ring.adapter.jetty :refer [run-jetty]]))

(defonce server (atom nil))

(defn stop-server []
  (when @server
    (.stop @server)))

(defn start-server [handler]
  (stop-server)
  (swap! server (fn [s]
                  (if s
                    (do (.start s) s)
                    (run-jetty handler {;; :port port
                                        :ssl? true
                                        :ssl-port (conf/server-port)
                                        :join? false})))))
