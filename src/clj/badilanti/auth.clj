(ns badilanti.auth
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
            [badilanti.conf :as conf]
            [clj-time.core :as time]
            [onelog.core :as log]
            [buddy.core.keys :as keys]
            [buddy.auth :refer [authenticated? throw-unauthorized]]
            [buddy.auth.backends :as backends]
            [buddy.auth.middleware :as midware]
            [buddy.auth.accessrules :as access]
            [buddy.hashers :as hs]
            [buddy.sign.jwt :as jwt]
            [buddy.auth.backends.token]
            [cemerick.friend :as friend]
            (cemerick.friend [workflows :as workflows]
                             [credentials :as creds]))
  (:gen-class))

(def pem-passphrase "Y3UPBPbSV43tBz5dKhco")

(defn find-user [name]
  (conf/conf :auth :users name))

(def private-key
  (delay (keys/private-key
          (io/resource (or (conf/conf :auth :private-key)
                           "auth_privkey.pem"))
          (or (conf/conf :auth :passphrase)
              pem-passphrase))))

(def public-key
  (delay (keys/public-key
          (io/resource (or (conf/conf :auth :public-key)
                           "auth_pubkey.pem")))))

(defn auth-user [credentials]
  (let [user (find-user (:username credentials))
        unauthed [false {:message "Invalid username or password"}]]
    (when (and user
               (hs/check (:password credentials) (:password user)))            
      (dissoc user :password))))

(defn expiration [validity]
  (-> (time/plus (time/now) validity)
      (buddy.sign.util/to-timestamp)))

(defn create-sjwt [credentials]
  (let [auth (auth-user credentials)]   
    (when auth
      (let [exp (expiration (time/minutes 1))]
        (jwt/sign (assoc auth :exp exp)
                  @private-key
                  {:alg :rs256})))))

(defn create-ejwt [credentials]
  (let [auth (auth-user credentials)]   
    (when auth
      (let [exp (expiration (time/minutes 1))]
        (jwt/encrypt (assoc auth :exp exp)
                     @public-key
                     {:alg :rsa-oaep
                      :enc :a128cbc-hs256})))))

(defn authenticate [req]
  (log/spy req)                         ; -wcp11/12/16.
  (if-let [token (create-ejwt (:params req))]
    {:status 201
     :body token
     :session (assoc (:session req) :identity token)}
    {:status 401 :body "Invalid credentials"}))

(defn decode-sjwt [token]
  (-> (jwt/unsign token @public-key {:alg :rs256})
      (update :roles #(set (map keyword %)))))

(defn decode-ejwt [token]
  (-> (jwt/decrypt token @private-key {:alg :rsa-oaep
                                       :enc :a128cbc-hs256})
      (update :roles #(set (map keyword %)))))

#_(decode-ejwt (create-ejwt {:username "guest" :password "guest"}))

(def sjwt-backend
  (delay
   (backends/jws {:secret @public-key
                  ;; Our Clojurescript client send us an OAuth token (Bearer) and we
                  ;; should accept that.
                  :token-name "Bearer"
                  :options {:alg :rs256}})))

(def ejwt-backend
  (delay
   (backends/jwe {:secret @private-key
                  ;; Our Clojurescript client send us an OAuth token (Bearer) and we
                  ;; should accept that.
                  :token-name "Bearer"
                  :options {:alg :rsa-oaep
                            :enc :a128cbc-hs256}})))

(def session-backend
  (delay
   (backends/session)))

(defn wrap-authentication [handler backend]
  (midware/wrap-authentication handler @backend))

(defn wrap-authorization [handler backend]
  (midware/wrap-authorization handler @backend))

(defn authorised-user? [request]
  ;; We should be doing something a bit more sophisticated such as
  ;; checking the roles if they match and stuff -wcp11/12/16.
  (authenticated? request))

(defn restrict [handler]
  (access/restrict handler
                   {:handler authorised-user?
                    :on-error (fn [request err]
                                {:status 403
                                 :headers {}
                                 :body (str "Not authorized" err)})}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Friend stuff

(defn wrap-authorize [handler roles]
  (friend/wrap-authorize handler roles))

(defn authenticate-basic [handler]
  (friend/authenticate handler
                       {:allow-anon? true
                        :unauthenticated-handler #(workflows/http-basic-deny "Badilanti" %)
                        :workflows [(workflows/http-basic
                                     :credential-fn #(creds/bcrypt-credential-fn (conf/conf :auth :users) %)
                                     :realm "Friend demo")]}))

(defn authenticate-form [handler]
  (friend/authenticate handler
                       {:credential-fn (partial creds/bcrypt-credential-fn (conf/conf :auth :users))
                        :workflows [(workflows/interactive-form)]}))

(defn logout [handler]
  (friend/logout handler))



