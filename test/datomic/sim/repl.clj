(ns datomic.sim.repl
  (:require [datomic.api :as d]))

(defn reset-conn
  "Reset connection to a scratch database. Used memory database if no
   URL passed in."
  ([]
     (reset-conn (str "datomic:mem://" (d/squuid))))
  ([uri]
     (d/delete-database uri)
     (d/create-database uri)
     (d/connect uri)))


(defn convenient
  []
  (in-ns 'user)
  (set! *warn-on-reflection* true)
  (set! *print-length* 20)
  (use '[datomic.api :as d])
  (use 'datomic.sim.util 'datomic.sim.hello-world)
  (require
   '[clojure.string :as str]
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.test.generative.generators :as gen]
   '[clojure.pprint :as pprint]
   '[datomic.sim :as sim]))
