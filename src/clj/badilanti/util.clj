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

