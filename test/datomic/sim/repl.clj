(ns datomic.sim.repl
  (:require [datomic.api :as d]
            [clojure.java.io :as io]))

(defn reset-conn
  "Reset connection to a scratch database. Used memory database if no
   URL passed in."
  ([]
     (reset-conn (str "datomic:mem://" (d/squuid))))
  ([uri]
     (d/delete-database uri)
     (d/create-database uri)
     (d/connect uri)))

(defn load-schema
  [conn resource]
  (let [m (-> resource io/resource slurp read-string)]
    (doseq [v (vals m)]
      (doseq [tx v]
        (d/transact conn tx)))))

(defn convenient
  []
  (in-ns 'user)
  (set! *warn-on-reflection* true)
  (set! *print-length* 20)
  (use '[datomic.api :as d])
  (use 'datomic.math)
  (use 'datomic.sim.util 'datomic.examples.trading-sim)
  (require
   '[clojure.string :as str]
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.test.generative.generators :as gen]
   '[clojure.pprint :as pprint]
   '[datomic.examples.trading :as trading] 
   '[datomic.sim :as sim]))
