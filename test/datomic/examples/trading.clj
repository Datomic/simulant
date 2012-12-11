(ns datomic.examples.trading
  (:use datomic.sim.util)
  (:require [datomic.api :as d]))

(defn trade
  [conn from to amount]
  (let [tx (d/tempid :db.part/tx)]
    (d/transact
     conn
     [[:db/add tx :transfer/amount amount]
      [:db/add tx :transfer/from (e from)]
      [:db/add tx :transfer/to (e to)]]))  )

(defn balance
  [db trader-id]
  (let [adds (d/q '[:find ?adds ?tx
                    :in $ ?trader
                    :where
                    [?tx :transfer/to ?trader]
                    [?tx :transfer/amount ?adds]]
                  db trader-id)
        subtracts (d/q '[:find ?subtracts ?tx
                         :in $ ?trader
                         :where
                         [?tx :transfer/from ?trader]
                         [?tx :transfer/amount ?subtracts]]
                       db trader-id)]
    (+ (getx (d/entity db trader-id) :trader/initialBalance)
       (apply + (map first adds))
       (- (apply + (map first subtracts))))))
