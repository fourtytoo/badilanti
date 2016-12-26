(ns badilanti.core
  (:require [reagent.core :as reagent :refer [atom]]
            [cljs.core.async :as async
             :refer [<! >! alts!]]
            [cljs-http.client :as http]
            [cljs-time.core :as time]
            [cljs-time.coerce :as timec]
            [cljs-time.format :as timef]
            [cljsjs.react-bootstrap]
            [cljsjs.fixed-data-table]
            [clojure.string :as string]
            [badilanti.common :as u])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(defonce auth-token (atom nil))
(defonce search-string (atom ""))
(defonce local-search (atom true))
(defonce profile-list (atom []))
(defonce current-profile (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn http-success? [reply]
  (let [{:keys [status success] :as answer} reply]
    (and success (= 2 (quot status 100)))))

(defn reply-body [reply]
  (when (http-success? reply)
    (:body reply)))

#_(defn rest-get [url parms]
  (let [chan (async/chan 1 (map reply-body))]
    (->> parms
         (assoc {:channel chan} :query-params)
         (http/get url))
    chan))

(defn fetch-auth-token
  ([username password]
   (http/post "/authenticate" {:edn-params
                               {:username username
                                :password password}}))
  ([token]
   (http/post "/authenticate" {:edn-params
                               {:token token}})))

(defn authenticate [token username password]
  (go (let [reply (<! (fetch-auth-token username password))]
        (if (http-success? reply)
          (reset! token (:body reply))
          ;; FIXME: must warn the user -wcp7/12/16.
          (reset! token nil)))))

#_(defn renew-token [token]
  (when token
    (go (let [reply (<! (fetch-auth-token token))]
          (if (http-success? reply)
            (:body reply))))))

(defn wrap-auth-proto [client request]
  (let [request' (assoc request :oauth-token @auth-token)]
    (async/map (fn [reply]
                 (let [{:keys [status success] :as answer} reply]
                   (if (or (= status 401)
                           (= status 403))
                     ;; (swap! auth-token renew-token)
                     (reset! auth-token nil)
                     ;; if new token, update internally
                     (when-let [token (get-in reply [:body :token])]
                       (reset! auth-token token))))
                 reply)
               [(client request')])))

;; (http/request (merge req {:method :get :url url}))

#_(defn rest-request [method url req]
  (async/map (fn [reply]
               (let [{:keys [status success] :as answer} reply]
                 (when (= 4 (quot status 100))
                   (reset! auth-token nil)))
               reply)
   [(http/request (merge req {:method method
                              :url url
                              :oauth-token @auth-token}))]))

(defn rest-get [url parms]
  (wrap-auth-proto (partial http/get url)
                   {:query-params parms}))

(defn rest-post [url params]
  (wrap-auth-proto (partial http/post url)
                   {:edn-params params}))

(def time-formatter
  (timef/formatters :mysql))

(defn format-time [time]
  (if (time/date? time)
    (timef/unparse time-formatter time)
    ""))

(defn fetch-profile [id]
  (rest-get "/api/profile" {:id id :content-type :plain}))

(defn load-profile [id profile]
  (reset! profile (str "Loading " id " ..."))
  (->> (fetch-profile id)
       <!
       :body
       (reset! profile)
       go))

#_(defn fetch-profile-list [search-string]
  (->> (string/split search-string #"\s+")
       (assoc {} :query)
       (rest-get "/api/find")))

(defn fetch-profile-list [search-string locally]
  (->> {:local locally :query search-string}
       (rest-get "/api/find")))

(defn load-profile-list [query locally profile-list]
  (reset! profile-list (str "Searching for " query " ..."))
  (go (let [reply (<! (fetch-profile-list query locally))]
        (if (http-success? reply)
          (reset! profile-list (:body reply))
          (reset! profile-list (str "Query failed with code " (:status reply)))))))

(defn input-value [id]
  (-> id js/document.getElementById .-value))

(defn focus [id]
  (-> id js/document.getElementById .focus))

(defn on-enter [f]
  (fn [e]
    (when (= 13 (.-charCode e))
      (f))))

(defn search-component [auth-token search-string profile-list]
  (let [submit #(load-profile-list @search-string @local-search profile-list)]
    [:div {:id "head" :class "search"}
     [:button {:on-click submit} "search"]
     [:input {:type "text"
              :class "entry"
              :value @search-string
              :on-key-press (on-enter submit)
              :on-change (fn [e]
                           (->> e .-target .-value (reset! search-string)))}]
     [:input {:type "checkbox"
              :class "check"
              :name "local"
              :value "whatever"
              ;; :value @local-search
              :on-change (fn [e] (swap! local-search not))
              }
      #_"locally"] "locally"
     [:img {:src "badilante.png"}]
     [:a {:on-click #(reset! auth-token nil)}
      "logout"]]))

(defn login-component [auth-token search-string profile-list]
  (let [submit #(authenticate auth-token
                              (input-value "username")
                              (input-value "password"))]
    [:div {:id "head" :class "auth"}
     "username:"
     [:input {:type "text"
              :id "username"
              :on-key-press (on-enter #(focus "password"))}]
     "password:"
     [:input {:type "password"
              :id "password"
              :on-key-press (on-enter submit)}]
     [:button {:on-click submit} "enter"]
     [:img {:src "badilante.png"}]]))

(defn head-component [auth-token search-string profile-list]
  (if @auth-token
    (search-component auth-token search-string profile-list)
    (login-component auth-token search-string profile-list)))

(defn profile-component [profile]
  (fn []
    (cond (nil? @profile) [:div]

          (string? @profile) [:iframe {:src @profile
                                       :style {:width "100%" :height "100%"}}]

          :else [:iframe {:src (:url @profile)
                          :style {:width "100%" :height "100%"}}])))

(reagent/render [head-component auth-token search-string profile-list]
                (js/document.getElementById "head"))

(reagent/render [profile-component current-profile]
                (js/document.getElementById "profile"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn striped-rows [rows]
  (map (fn [[tr attrs & rest] cl]
         (into [tr (assoc attrs :class cl)] rest))
       rows (cycle ["odd" "even"])))

(defn profile-list-component [profile-list]
  (if (string? @profile-list)
    [:div [:span @profile-list]]
    [:div {:class "list"}
     (when @profile-list
       [:span "hits: "
        (count @profile-list)])
     (if @profile-list
       [:table
        [:tbody
         (->> @profile-list
              (map (fn [row]
                     (let [pd (get row :personal-data)]
                       [:tr {}
                        [:td
                         [:img {:src (or (get pd :photo) "dummy-photo.png")
                                :style {:height "5em"}}]]
                        [:td {:on-click #(js/open (str  "/api/profile/" (:board row)
                                                        "/" (:id row)))}
                         (:id pd)
                         [:br]
                         (get pd :hourly-rate)
                         [:br]
                         (get pd :address)
                         [:br]
                         (get pd :last-update)]
                        [:td {:style {:width "4em"}}
                         (:score row)]])))
              striped-rows)]]
       [:span "Enter a search string above"])]))

(reagent/render [profile-list-component profile-list]
                (js/document.getElementById "profile-list"))
