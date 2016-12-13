(ns badilanti.gulp
  (:require [jsoup.soup :as soup]
            [clojure.string :as string]
            [org.httpkit.client :as http]
            [clojure.pprint :as pp]
            [onelog.core :as log]
            [badilanti.util :refer :all]
            [badilanti.conf :as conf]
            [clojure.java.io :as io]
            [clj-time.core :as time]))

(defn search-form-url []
  (or (conf/conf :gulp :search-form-url)
      "https://www.gulp.de/cgi-gulpsearch/enter.exe/FINDFORM?FindProfilTotal.html"))

(defn search-url []
  (or (conf/conf :gulp :search-url)
      "https://www.gulp.de/cgi-gulpsearch/FindProfil.exe/Find?V01011711"))

(defn auth-parms []
  [(conf/conf :boards "gulp" :user)
   (conf/conf :boards "gulp" :password)])

(def cache (make-lru-cache 128))

(defn reset-cache []
  (empty-cache cache))

(defn http-get [url & [opts]]
  (-> url
      (http/get (merge opts
                       {:follow-redirects true
                        :basic-auth (auth-parms)}))
      deref))

(defn soup-get [uri]
  (soup/get! uri
             :follow-redirects true
             :auth (apply soup/basic-auth (auth-parms))))

(defn http-post [url & [opts]]
  (-> url
      (http/post (merge opts
                       {:follow-redirects true
                        :basic-auth (auth-parms)}))
      deref))

(defn german-date [dt]
  (str (time/day dt) "."
       (time/month dt) "."
       (mod (time/year dt)
            100)))

(defn next-availability []
  (german-date (time/plus (time/now) (time/days (or (conf/conf :availability) 7)))))

(defn last-update []
  (german-date (time/minus (time/now) (time/months (or (conf/conf :last-update) 12)))))

(defn search-form-parms [query zip]
  {"chkbEinsatz1" true
   "txtCP" query
   "txtZIP" zip
   "txtVerf" (next-availability)
   "txtVerfProz" 70
   "txtVerfVorOrt" 70
   ;; "txtNotVerf" "24.05.16"
   "txtDateUpdated" (last-update)
   "nLanguage" 0 ;0=German, 1=English
   "filename" "FindProfilTotal.html" ;hidden
   })

(defn stringify-opts [opts]
  (reduce-kv (fn [m k v]
               (assoc m k (str v)))
             {} opts))

(def zip-codes
  (delay (->> (soup-get (search-form-url))
              (soup/select "select[name=txtZIP] option[value]")
              (soup/attr "value")
              (filter #(string/starts-with? % "D")))))

(defn post-profiles-search- [query]
  (http-post (search-url)
             {:follow-redirects true
              :auth (apply soup/basic-auth (auth-parms))
              :form-params (search-form-parms query "")}))

(defn post-profiles-search [query zip]
  (soup/post! (search-url)
              :follow-redirects true
              :auth (apply soup/basic-auth (auth-parms))
              :data (stringify-opts (search-form-parms query zip))))

(defn extract-search-result-link [page]
  (->> page
       (soup/select "div#tabzentr div table a[href]")
       (soup/attr "abs:href")
       second))

(defn page-overload-error? [page]
  (->> page
       (soup/select "h1")
       soup/text
       first
       (and true)))

(defn launch-profiles-search [query zip]
  (let [page (post-profiles-search query zip)
        result-link (extract-search-result-link page)]
    (cond result-link result-link
          (page-overload-error? page) :overload
          :else (throw (ex-info "Failed to find a result link in page"
                                {:page page})))))

(defn extract-search-continuations [body]
  (->> body
       (soup/select "div > table > tbody > tr > td > b > a[href]")
       (soup/attr "abs:href")
       set sort))

(defn uri->id [uri]
  (-> uri
      (string/split #"\?")
      second))

(defn extract-search-hits [body]
  (->> body
       (soup/select "a.searchresult[href]")
       (soup/attr "abs:href")
       (map uri->id)))

(defn search-profiles [query zip]
  (if zip
    (loop []
      (let [result-uri (launch-profiles-search query zip)]
        (cond (= :overload result-uri)
              (do
                (log/info "System overload; waiting 3 mins")
                (Thread/sleep (* 3 60 1000))
                (recur))

              result-uri
              (let [page (soup-get result-uri)]
                (concat (extract-search-hits page)
                        (mapcat (fn [uri]
                                  (-> (soup-get uri)
                                      extract-search-hits))
                                (extract-search-continuations page)))))))
    (mapcat (fn [zip]
              (search-profiles query zip))
            @zip-codes)))

;; (count (search-profiles "clojure" nil))
;; (count (search-profiles "java" nil))
;; (launch-profiles-search "clojure" "R5")
;; (post-profiles-search "clojure" "R5")

(defn profile-url [id]
  (str "https://www.gulp.de/cgi-gulpsearch/gp.exe/ubprof?" id))

(defn fetch-profile [id]
  (soup-get (profile-url id)))

#_(http-get (profile-url 60208))

(defn get-profile [id]
  (log/debug "get-profile " id)
  (cached cache id (fetch-profile id)))

(defn attr [selector element]
  (.attr element selector))

(defn text [element]
  (.text element))

(defn extract-table-columns [row]
  (->> row
       (soup/select "td")
       soup/text
       (map string/lower-case)))

(defn extract-skilltable [chapter]
  (let [table (soup/select "table.skilltable" chapter)]
    (when-not (empty? table)
      (->> table
           (soup/select "tr")
           (map extract-table-columns)
           (map vec)
           #_(into {})))))

(defn extract-chapter-content [chapter]
  (->> chapter
       (soup/select "div.chapter_content")
       soup/text
       first
       string/lower-case))

(defn match-positions [re s]
  (let [m (re-matcher re s)]
    (take-while (comp not nil?)
                (repeatedly (fn []
                              (when (.find m)
                                [(.start m)
                                 (.end m)]))))))

(defn longest-whitespace [string]
  (->> string
       (match-positions #"\s+")
       (reduce (fn [longest e]
                 (let [[ls le] longest
                       [es ee] e]
                   (if (> (- ee es)
                          (- le ls))
                     e
                     longest)))
               [0 0])))

(defn guess-second-column-offset [rows]
  (->> rows
       (map (comp second longest-whitespace))
       frequencies
       (sort-by val >)
       ffirst))

(defn string-empty? [string]
  (zero? (count string)))

(def pd-headers {"Jahrgang" :birth-year
                 "Profil erstellt am" :profile-created
                 "Wohnort" :address
                 "Verfügbar ab" :available-starting
                 "Personen-ID" nil
                 "Profil zuletzt geändert am" :last-update
                 "Lesen von Projektanfragen" :WTF
                 "Stundensatz" :hourly-rate
                 "Staatsbürgerschaft" :citizenship})

(defn pd-header->keyword [id]
  (get pd-headers id id))

(defn extract-personal-data [chapter]
  (let [photo (->> chapter
                   (soup/select "div.photo img[src]")
                   (soup/attr "abs:src")
                   first)
        data (->> chapter
                  (soup/select "pre")
                  soup/text
                  first
                  string/split-lines)
        second-col-offset (guess-second-column-offset data)]
    (->> data
         (map (fn [line]
                [(string/trimr (subs line 0 second-col-offset))
                 (string/trim (subs line second-col-offset))]))
         (reduce (fn [[[xk xv] & xs] [k v]]
                   (if (string-empty? k)
                     (cons [xk (str xv " " v)] xs)
                     (cons [k v] (cons [xk xv] xs))))
                 (list))
         (map (juxt (comp pd-header->keyword first) second))
         (remove (comp nil? first))
         (into {:photo photo
                #_(when photo
                    (-> photo
                        http-get 
                        :body))}))))

(def chapter-prefix "chapter_")

(def chapter-titles {"PJ" :projects
                     "EO" :geographic-area
                     "AB" :education
                     "FS" :spoken-languages
                     "BS" :operating-systems
                     "PS" :programming-languages
                     "DB" :databases
                     "DC" :ipc
                     "SS" :standards
                     "BR" :business-domains
                     "RF" :references
                     "fachschwer" :strengths
                     "kommentar" :comments
                     "personendaten" :personal-data})

(def parsers {:personal-data #(extract-personal-data %)})

(defn chapter-id->keyword [id]
  (let [title (subs id (count chapter-prefix))]
    (get chapter-titles title title)))

(defn extract-chapter [element]
  (let [id (-> (attr "id" element)
               chapter-id->keyword)]
    [id element]))

(defn parse-chapter [id chapter]
  (let [parse (parsers id)]
    [id (if parse
          (parse chapter)
          (or #_(extract-skilltable chapter)
              (extract-chapter-content chapter)
              chapter))]))

(defn extract-chapters [profile]
  (->> profile
       (soup/select (str "div.chapter[id^=" chapter-prefix "]"))
       (map extract-chapter)
       (map (fn [[id chapter]]
              (parse-chapter id chapter)))
       (into {})))

(defn candidate-profile [id]
  (-> id
      get-profile
      extract-chapters
      (assoc :id id)
      (assoc :board :gulp)))

(defn profile-match [profile patterns]
  (let [langs (:programming-languages profile)
        standards (:standards profile)
        dbs (:databases profile)
        oss (:operating-systems profile)
        strengths (:strengths profile)
        projects (:projects profile)]
    (->> patterns
         (map (fn [p]
                (let [p (-> p string/trim string/lower-case)
                      re (re-pattern (str "(?iu)\\b" (-> p re-quote re-wbound) "\\b"))]
                  (+ (->> [langs dbs oss]
                          (remove nil?)
                          (map #(if (re-find re %) 1 0))
                          (reduce +)
                          (min 1))
                     (if projects
                       (* 0.25 (count (re-seq re projects)))
                       0)))))
         (reduce +))))

(defn rank-profiles [patterns profiles]
  (->> profiles
       (map (fn [profile]
              (assoc profile :match-rank (log/spy (profile-match profile patterns)))))
       (sort-by :match-rank >)))

#_(->> [134616 173307 60208 11348 129983 159295 131316]
       (map candidate-profile)
       (rank-profiles ["cobol" "delphi"])
       (map (juxt :id :match-rank)))

;; (candidate-profile 60208)
;; (rank-profiles ["cobol"] [(candidate-profile 159988)])
;; (rank-profiles ["clojure"] [(candidate-profile 129375)])
