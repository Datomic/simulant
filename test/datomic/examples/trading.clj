(ns datomic.examples.trading
  (:use datomic.api datomic.sim.util))

(defn trade
  [conn from to amount]
  (let [tx (tempid :db.part/tx)]
    (transact
     conn
     [[:db/add tx :transfer/amount amount]
      [:db/add tx :transfer/from (e from)]
      [:db/add tx :transfer/to (e to)]]))  )

(defn balance
  [db trader-id]
  (let [adds (q '[:find ?adds ?tx
                  :in $ ?trader
                  :where
                  [?tx :transfer/to ?trader]
                  [?tx :transfer/amount ?adds]]
                db trader-id)
        subtracts (q '[:find ?subtracts ?tx
                       :in $ ?trader
                       :where
                       [?tx :transfer/from ?trader]
                       [?tx :transfer/amount ?subtracts]]
                     db trader-id)]
    (+ (getx (entity db trader-id) :trader/initialBalance)
       (apply + (map first adds))
       (- (apply + (map first subtracts))))))
