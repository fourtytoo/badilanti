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
            [clj-time.coerce]
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

;; Just like jwt/unsign but don't throw an exception if token is expired
(defn unsign [message pkey opts]
  (try
    (-> (buddy.sign.jws/unsign message pkey opts)
        (buddy.core.codecs/bytes->str)
        (cheshire.core/parse-string true))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (throw (ex-info "Message seems corrupt or manipulated."
                      {:type :validation :cause :signature})))))

;; Just like jwt/decrypt but don't throw and exception if token is expired
(defn decrypt [message pkey opts]
   (try
     (-> (buddy.sign.jwe/decrypt message pkey opts)
         (buddy.core.codecs/bytes->str)
         (cheshire.core/parse-string true))
     (catch com.fasterxml.jackson.core.JsonParseException e
       (throw (ex-info "Message seems corrupt or manipulated."
                       {:type :validation :cause :signature})))))

(defn encode-sjwt [claims]
  (jwt/sign claims @private-key {:alg :rs256}))

(defn decode-sjwt [token]
  (when token
    (-> (unsign token @public-key {:alg :rs256})
        (update :roles #(set (map keyword %))))))

(defn encode-ejwt [claims]
  (jwt/encrypt claims
               @public-key
               {:alg :rsa-oaep
                :enc :a128cbc-hs256}))

(defn decode-ejwt [token]
  (when token
    (-> (decrypt token @private-key {:alg :rsa-oaep
                                     :enc :a128cbc-hs256})
        (update :roles #(set (map keyword %))))))

#_(decode-ejwt (create-ejwt {:username "guest" :password "guest"}))

(defn auth-user [username password]
  (let [user (find-user username)]
    (when (and user
               (hs/check password (:password user)))
      (dissoc user :password))))

(def token-grace-period (time/days 1))

(defn valid-token-renew [claims]
  (when (time/after? (time/plus (clj-time.coerce/from-long (* 1000 (get claims :exp 0)))
                                token-grace-period)
                     (time/now))
    claims))

(defn standard-claims [validity]
  (let [now (time/now)]
    {:iat (buddy.sign.util/to-timestamp now)
     :exp (-> (time/plus now validity)
              buddy.sign.util/to-timestamp)
     :jti (buddy.core.codecs/bytes->hex
           (buddy.core.nonce/random-nonce 32))}))

(defn create-sjwt [credentials]
  (log/spy (:username credentials))
  (let [claims (or (-> credentials :token decode-sjwt valid-token-renew)
                   (auth-user (:username credentials) (:password credentials)))]
    (when claims
      (-> (time/days 1)
          standard-claims
          (merge claims)
          encode-sjwt))))

(defn create-ejwt [credentials]
  (log/spy (:username credentials))
  (let [claims (or (-> credentials :token decode-ejwt valid-token-renew)
                   (auth-user (:username credentials) (:password credentials)))]
    (when claims
      (-> (time/days 1)
          standard-claims
          (merge claims)
          encode-ejwt))))

(defn refresh-ejwt [token]
  (-> (time/minutes (or (conf/conf :session-expiration) 1))
      standard-claims
      (merge (decode-ejwt token))
      encode-ejwt))

(defn refresh-token [token]
  (refresh-ejwt token))

(defn authenticate [req]
  (if-let [token (create-ejwt (:params req))]
    {:status 201
     ;; :session (assoc (:session req) :identity token)
     :body token}
    {:status 401
     :body "Invalid credentials"}))

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

(defn req-token [req]
  (buddy.auth.protocols/-parse @ejwt-backend req))

(defn refresh-req-token [req response]
  (let [token (req-token req)]
    (if (map? (:body response))
      (assoc-in response [:body :token] (refresh-token token))
      ;; do not add a token if response is not a map
      response)))

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
