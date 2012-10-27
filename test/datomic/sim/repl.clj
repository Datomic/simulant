(ns datomic.sim.repl
  (:require [datomic.api :as d]))

(defn txresult-entity
  [txresult eid]
  (let [{:keys [db-after tempids]} txresult]
    (d/entity db-after (d/resolve-tempid db-after tempids eid))))

(defn scratch-conn
  "Create a connection to an anonymous, in-memory database."
  []
  (let [uri (str "datomic:mem://" (d/squuid))]
    (d/delete-database uri)
    (d/create-database uri)
    (d/connect uri)))

(defn count-by
  [db attr]
  (->> (d/q '[:find (count ?e)
              :in $ ?attr
              :where [?e ?attr]]
            db attr)
       ffirst))

(defn convenient
  []
  (in-ns 'user)
  (set! *warn-on-reflection* true)
  (set! *print-length* 20)
  (use '[datomic.api :as d])
  (require
   '[clojure.string :as str]
   '[clojure.java.io :as io]
   '[clojure.pprint :as pprint]))
