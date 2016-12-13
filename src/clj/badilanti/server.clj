(ns badilanti.server
  (:require [clojure.java.io :as io]
            [compojure.core :refer [ANY GET PUT POST DELETE context defroutes]]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults site-defaults secure-api-defaults]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ring.middleware.logger :refer [wrap-with-logger]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.util.response :as response]
            [ring.util.anti-forgery :refer [anti-forgery-field]]
            [badilanti.kit :refer [start-server]]
            #_[badilanti.jetty :refer [start-server]]
            [clojure.xml :as xml]
            [clojure.data.xml :as dxml]
            [clojure.zip :refer [xml-zip]]
            [clojure.data.zip :as dz]
            [clojure.data.zip.xml :as dzx]
            [clojure.string :as string]
            [clojure-mail.core :as mail]
            [clojure-mail.message :as message]
            [hiccup.core :refer [html]]
            [jsoup.soup :as soup]
            [badilanti.gulp :as gulp]
            [badilanti.util :refer :all]
            [badilanti.db :as db]
            [badilanti.conf :as conf]
            [badilanti.auth :as auth]
            [onelog.core :as log]
            [clj-time.core :as time])
  (:gen-class))

(defn edn-response [body]
  (-> (pr-str body)
      response/response
      (response/content-type "application/edn")))

(defn profile-response [profile]
  (edn-response profile))

(defn list-profiles [query]
  (log/debug "list-profiles " query)
  (log/spy (gulp/search-profiles query nil)))

(string/split "cobol" #"\s+")

(defn find-profiles [query]
  (log/debug "find-profiles " query)
  (->> (list-profiles query)
       (map gulp/candidate-profile)
       (gulp/rank-profiles (string/split query #"\s+"))
       (map #(select-keys % [:id :personal-data :match-rank]))))

(defn enqueue-update [id]
  ;; XXX: yet to be done -wcp28/11/16.
  )

(defn profile-response [board id]
  (if-let [p (db/get-profile board id)]
    (response/response (:raw-data p))
    (do
      (enqueue-update id)
      (response/redirect (gulp/profile-url id)))))

(defn configure [request]
  (let [{:keys [path value]} (:params request)]
    (log/info "configuration path " path " changed to: " value)
    (conf/configure! path value)
    (response/response (str path " := " value))))

(defroutes api-routes
  (GET "/find" [query]
       #_(edn-response [{:id 1234 :hourly-rate "lotta" :address "moon" :last-update 0 :personal-data "cool" :board "tavola"}])
       (edn-response (find-profiles query)))
  (POST "/configure" req configure)
  (GET "/profile/:board/:id" [board id]
       (profile-response board id)))

(defroutes routes
  (GET "/" _
       (response/redirect "/index.html"))
  ;; this route is required by Friend -wcp6/12/16.
  #_(GET "/login" _
       (response/redirect "/login.html"))
  (POST "/authenticate" req auth/authenticate)
  (context "/api" [token]
           (-> api-routes
               auth/restrict
               #_auth/wrap-authorization
               #_wrap-auth-token
               ))
  (auth/logout (ANY "/logout" _ (response/redirect "/")))
  ;; this will serve the css style files and all the rest
  (route/resources "/")
  (route/not-found "Not Found"))

(defn wrap-time-limit [handler]
  (fn [req]
    (if (time/before? (time/now) (time/date-time 2016 12 25))
      (handler req)
      (response/redirect "http://google.com"))))

(def http-handler
  (-> routes
      (auth/wrap-authorization auth/session-backend)
      (auth/wrap-authentication auth/session-backend)
      (wrap-defaults api-defaults)
      ring.middleware.session/wrap-session
      (wrap-restful-format :formats [:edn :json-kw])
      #_(auth/authenticate-basic)
      #_(auth/authenticate-form)
      wrap-time-limit
      #_wrap-stacktrace
      wrap-content-type
      wrap-gzip
      wrap-with-logger))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn -main [& args]
  (log/start!)
  (start-server #'http-handler))
