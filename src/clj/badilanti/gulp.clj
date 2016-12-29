(ns badilanti.gulp
  (:require [jsoup.soup :as soup]
            [clojure.string :as string]
            [org.httpkit.client :as http]
            [clojure.pprint :as pp]
            [onelog.core :as log]
            [badilanti.util :refer :all]
            [badilanti.conf :as conf]
            [badilanti.db :as db]
            [clojure.java.io :as io]
            [clj-time.core :as time]
            [clj-time.coerce]))


(defn search-form-url []
  (or (conf/conf :gulp :search-form-url)
      "https://www.gulp.de/cgi-gulpsearch/enter.exe/FINDFORM?FindProfilTotal.html"))

(defn search-url []
  (or (conf/conf :gulp :search-url)
      "https://www.gulp.de/cgi-gulpsearch/FindProfil.exe/Find?V01011711"))

(defn auth-parms []
  [(conf/conf :boards "gulp" :username)
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

(defn soup-get [url]
  (soup/get! (str url)
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
       (soup/attr "abs:href")))

(defn search-profiles-by-zip [query zip]
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
              (search-profiles-by-zip query zip))
            @zip-codes)))

(defn search-profile-ids [query]
  (-> (search-profiles-by-zip query nil)
      (map uri->id)))

(defn extract-last-update [body]
  (->> (if (string? body)
         (soup/parse body)
         body)
       (soup/select "meta[name=date]")
       (soup/attr "content")
       first
       clj-time.format/parse))

(defn file-modtime [file]
  (clj-time.coerce/from-long (.lastModified (io/file file))))

(defn profile-uptodate? [file body]
  (time/after? (file-modtime file) (extract-last-update body)))

(defn download-profile [uri]
  (let [file (str "/tmp/badilanti/" (uri->id uri))
        profile (:body (http-get uri))]
    (io/make-parents file)
    (when-not (profile-uptodate? file profile)
      (spit file profile))))

(defn download-profiles [query]
  (->> (search-profiles-by-zip query nil)
       (pmap download-profile)))

(defn id->uri [id]
  (str "https://www.gulp.de/cgi-gulpsearch/gp.exe/ubprof?" id))

(defn fetch-profile [id]
  (soup-get (id->uri id)))

(defn get-profile-document [id]
  (cached cache id
          (convert-links-to-URLs! (fetch-profile id))))

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
          (or (extract-chapter-content chapter)
              chapter))]))

(defn parse-profile-document [profile]
  (->> profile
       (soup/select (str "div.chapter[id^=" chapter-prefix "]"))
       (map extract-chapter)
       (map (fn [[id chapter]]
              (parse-chapter id chapter)))
       (into {:updated (extract-last-update profile)
              :raw-profile (.outerHtml profile)
              })))

(defn parse-profile-string [profile]
  (-> profile
      soup/parse
      parse-profile-document))

(defn candidate-profile [id]
  (-> id
      get-profile-document
      parse-profile-document
      (assoc :id id)
      (assoc :board "gulp")))

(defn clean-text [text]
  ;;TODO: should we also remove punctuation and numbers? -wcp29/12/16.
  (just-one-space text))

(defn profile-skills [profile]
  (->> [:programming-languages :operating-systems :databases :ipc :standards :strengths]
       (map (partial get profile))
       (string/join " ")
       clean-text))

(defmethod db/normalise-profile "gulp" [profile]
  (merge {:skills (profile-skills profile)
          :projects (-> profile :projects clean-text)}
         (select-keys profile [:personal-data :raw-profile])))


