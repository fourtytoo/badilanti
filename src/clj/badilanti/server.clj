(ns badilanti.server
  (:require [clojure.java.io :as io]
            [compojure.core :refer [ANY GET PUT POST PATCH DELETE context defroutes]]
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
  (-> body
      response/response
      (response/content-type "application/edn")))

(defn list-profiles [query]
  (log/debug "list-profiles " query)
  (gulp/search-profile-ids query))

(defn find-profiles [query local]
  (if local
    (db/search-profiles query)
    (->> (list-profiles query)
         (map gulp/candidate-profile)
         (gulp/rank-profiles (string/split query #"\s+"))
         (map #(select-keys % [:id :personal-data :score])))))

(defn enqueue-update [board id]
  ;; XXX: yet to be done -wcp28/11/16.
  )

(defn store-profile [profile]
  (db/put-profile (:board profile) (str (:id profile))
                  (db/normalise-profile profile)))

;; XXX: -wcp19/12/16.
(defn test-update-db-profiles []
  (pmap (comp store-profile gulp/candidate-profile)
        [134616 173307 60208 11348 129983 159295 131316]))

#_(test-update-db-profiles)

(defn profile-redirection [board id]
  (enqueue-update board id)
  (response/redirect (gulp/id->uri id)))

(defn get-profile [board id]
  (if-let [p (db/get-profile board id)]
    (-> (:raw-profile p)
        response/response
        (response/content-type "text/html"))
    (profile-redirection board id)))

(defn post-profile [board id body]
  (let [identification {:board board :id id}
        res (-> (if (string? body)
                  (gulp/parse-profile-string body)
                  body)
                (merge identification)
                store-profile)]
    (response/response (assoc identification :result res))))

(defn put-profile [board id body]
  (post-profile board id body))

(defn patch-profile [board id new-attrs]
  (let [old (db/get-profile board id)]
    (if old
      (post-profile board id (merge old new-attrs))
      (-> (response/response {:board board :id id :error "Not found"})
          (response/status 404)))))

(defn delete-profile [board id]
  (let [[ok? res] (db/delete-profile board id)]
    (-> (response/response {:board board :id id :result res})
        (response/status (if ok? 200 404)))))

(defn configure [request]
  (let [{:keys [path value]} (:params request)]
    (log/info "configuration path " path " changed to: " value)
    (conf/configure! path value)
    (response/response (str path " := " value))))

(defn wrap-token-refresh [handler]
  (fn [req]
    (auth/refresh-req-token req (handler req))))

(defroutes api-routes
  (GET "/find" [query local]
       (edn-response {:hits (find-profiles query true)}))
  (POST "/configure" req configure)
  (POST "/profile/:board/:id" [board id body]
        (post-profile board id body))
  (PUT "/profile/:board/:id" [board id body]
        (put-profile board id body))
  (PATCH "/profile/:board/:id" [board id body]
         (patch-profile board id body))
  (DELETE "/profile/:board/:id" [board id]
          (delete-profile board id))
  (GET "/profile/:board/:id" [board id]
       (get-profile board id)))

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
  (GET "/profile-redirection/:board/:id" [board id]
       (profile-redirection board id))
  (auth/logout (ANY "/logout" _ (response/redirect "/")))
  ;; this will serve the css style files and all the rest
  (route/resources "/")
  (route/not-found "Not Found"))

(defn wrap-time-limit [handler]
  (fn [req]
    (if (time/before? (time/now) (time/date-time 2017 3 1))
      (handler req)
      (response/redirect "http://disney.com"))))

(def http-handler
  (-> routes
      #_(auth/wrap-authorization auth/session-backend)
      #_(auth/wrap-authentication auth/session-backend)
      (auth/wrap-authorization auth/ejwt-backend)
      (auth/wrap-authentication auth/ejwt-backend)
      wrap-token-refresh
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
