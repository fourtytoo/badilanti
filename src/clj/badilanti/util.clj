(ns badilanti.util
  (:require [clojure.core.cache :as cache]))

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
