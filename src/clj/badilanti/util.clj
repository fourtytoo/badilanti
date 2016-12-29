(ns badilanti.util
  (:require [clojure.core.cache :as cache]
            [jsoup.soup :as soup]
            [pathetic.core :as path]
            [clojure.string :as string]
            [clojure.java.io :as io]))

(defn show [thing]
  (clojure.pprint/pprint thing)
  thing)

(defn re-quote [str]
  (java.util.regex.Pattern/quote str))

(defn re-wbound [s]
  (str "\\b" s "\\b"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-lru-cache [& [size]]
  (atom (cache/lru-cache-factory {} :threshold (or size 32))))

(defn empty-cache [cache]
  (swap! cache #(.empty %)))

;; cache here is assumed to be an atom
(defn call-cached [cache key f]
  (if (cache/has? @cache key)
    (do
      (swap! cache #(cache/hit % key))
      (cache/lookup @cache key))
    (let [v (f)]
      (swap! cache #(cache/miss % key v))
      v)))

(defmacro cached [cache key & forms]
  `(call-cached ~cache ~key (fn [] ~@forms)))

(defn as-collection [thing]
  (if (coll? thing)
    thing
    [thing]))

(defn whitespace? [char]
  (Character/isWhitespace char))

(defn uri-resolve
  "Behaves like resolve on the path part of a URI. If URI happens to
  be already a URL it is returned as it is.  The path is not
  normalized; see url-normalize instead."
  [base-url uri]
  (try
    (io/as-url uri)
    uri
    (catch java.net.MalformedURLException e
      (let [[pre-path path post-path] (path/split-url-on-path base-url)]
        (str pre-path (path/resolve path uri) post-path)))))

(defn uri-complete [base uri]
  (path/url-normalize (uri-resolve base uri)))

(defn attr
  ;; getter
  ([attr-key element]
   (.attr element attr-key))
  ;; setter
  ([attr-key element value]
   (.attr element attr-key value)))

(defn text [element]
  (.text element))

(defn attr-update! [element attr-key f]
  (attr attr-key element
        (f (attr attr-key element))))

#_(defn convert-links-to-URLs! [doc base]
  (run! #(attr-update! % "href" (partial uri-complete base))
        (soup/select "[href]" doc))
  (run! #(attr-update! % "src" (partial uri-complete base))
        (soup/select "[src]" doc))
  doc)

(defn convert-links-to-URLs! [doc]
  (run! #(attr "href" % (attr "abs:href" %))
        (soup/select "[href]" doc))
  (run! #(attr "src" % (attr "abs:src" %))
        (soup/select "[src]" doc))
  doc)

#_(convert-links-to-URLs! (fetch-profile 131316))

;; char 160 is, according to ISO 8859-1, a "non-breakable
;; space" (whatever that means), which is not recognised by the \s
;; regexp
(def whitespace-regex (re-pattern (str "[\\s" (char 160) "]+")))

(defn just-one-space [string]
  (string/replace string whitespace-regex " "))

#_(just-one-space "foo     bar     baz")

