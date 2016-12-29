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

(defn fetch-profile [board id]
  (rest-get (str "/api/profile/" board "/" id) {}))

(defn load-profile [board id atom]
  ;; (reset! atom (str "Loading " id " ..."))
  (->> (fetch-profile board id)
       <!
       :body
       (reset! atom)
       go))

(defn fetch-profile-list [search-string locally]
  (->> {:local locally :query search-string}
       (rest-get "/api/find")))

(defn load-profile-list [query locally profile-list]
  (reset! profile-list (str "Searching for " query " ..."))
  (go (let [reply (<! (fetch-profile-list query locally))]
        (if (http-success? reply)
          (reset! profile-list (-> reply :body :hits))
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
              }]
     "locally"
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

(defn profile-component [current-profile]
  (fn []
    (let [p @current-profile]
      (if (nil? p)
        [:div]
        [:iframe {:src (str "/api/profile/" (:board p) "/" (:id p))
                  :class "profile"}]))))

(reagent/render [head-component auth-token search-string profile-list]
                (js/document.getElementById "head"))

(reagent/render [profile-component current-profile]
                (js/document.getElementById "profile"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn striped-rows [rows]
  (map (fn [[tr attrs & rest] cl]
         (into [tr (assoc attrs :class cl)] rest))
       rows (cycle ["odd" "even"])))

(defn profile-list-component [profile-list current-profile]
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
                        [:td {:on-click
                              ;; #(load-profile (:board row) (:id row) current-profile)
                              ;; #(js/open (str  "/profile-redirection/" (:board row) "/" (:id row)))
                              #(reset! current-profile row)
                              }
                         (:id pd)
                         [:br]
                         (get pd :hourly-rate)
                         [:br]
                         (get pd :address)
                         [:br]
                         (get pd :last-update)]
                        [:td {:class "number"}
                         (re-find #"\d+\.\d\d" (str (* 100 (:score row)) 1))]])))
              striped-rows)]]
       [:span "Enter a search string above"])]))

(reagent/render [profile-list-component profile-list current-profile]
                (js/document.getElementById "profile-list"))
