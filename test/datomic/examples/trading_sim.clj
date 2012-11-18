(ns datomic.examples.trading-sim
  (:use datomic.api datomic.sim.util)
  (:require
   [clojure.java.io :as io]
   [clojure.test.generative.generators :as gen]
   [datomic.sim :as sim]
   [datomic.examples.trading :as trading]))

(defn create-test
  "Returns test entity"
  [conn model test]
  (require-keys test :db/id :test/duration)
  (-> @(transact conn [(assoc test
                         :test/type :test.type/trading
                         :model/_tests (e model))])
      (tx-ent (:db/id test))))

(defn create-traders
  "Returns trader ids sorted"
  [conn test]
  (let [model (-> test :model/_tests solo)
        ids (repeatedly (:model/traderCount model) #(tempid :test))
        txresult (->> ids
                      (map (fn [id] {:db/id id
                                     :agent/type :agent.type/trader
                                     :test/_agents (e test)}))
                      (transact conn))]
    (tx-entids @txresult ids)))

(defn generate-trade
  "Generate a trade from trader ord, based on the model"
  [test from-trader traders at-time]
  (let [model (-> test :model/_tests first)]
    [[{:db/id (tempid :test)
       :agent/_actions (e from-trader)
       :action/atTime at-time
       :action/type :action.type/trade
       :transfer/from (e from-trader)
       :transfer/to (e (gen/rand-nth traders))
       :transfer/amount (long (gen/geometric (/ 1 (:model/meanTradeAmount model))))}]]))

(defn generate-trader-trades
  "Generate all actions for trader ord, based on model"
  [test from-trader traders]
  (let [model (-> test :model/_tests first)
        limit (:test/duration test)
        step #(gen/geometric (/ 1 (hours->msec (:model/meanHoursBetweenTrades model))))]
    (->> (reductions + (repeatedly step))
         (take-while (fn [t] (< t limit)))
         (mapcat #(generate-trade test from-trader traders %)))))

(defn generate-all-trades
  [test traders]
  (mapcat
   (fn [from-trader] (generate-trader-trades test from-trader traders))
   traders))

(defmethod sim/create-test :model.type/trading
  [conn model test]
  (let [test (create-test conn model test)
        traders (create-traders conn test)]
    (transact-batch conn (generate-all-trades test traders) 1000)
    (entity (db conn) (e test))))

(defmethod sim/create-sim :test.type/trading
  [sim-conn test sim]
  (let [model (-> test :model/_tests solo)
        schema (-> "datomic-sim/trading.dtm" io/resource slurp read-string)
        uri (doto (getx sim :sim/systemURI)
              (create-database))
        trading-conn (connect uri)]
    (doseq [k [:trading :transfer]]
      (doseq [tx (get schema k)]
        (transact trading-conn tx)))
    (transact
     trading-conn
     (map
      (fn [agent]
        {:db/id (tempid :db.part/user)
         :trader/id (:db/id agent)
         :trader/initialBalance (getx model :model/initialBalance)})
      (:test/agents test)))
    (-> @(transact sim-conn (sim/construct-basic-sim test sim))
        (tx-ent (:db/id sim)))))

(defmethod sim/perform-action :action.type/trade
  [action sim]
  (let [trade-conn (connect (:sim/systemURI sim))
        trade-db (db trade-conn)
        amount (:transfer/amount action)
        from (find-by trade-db :trader/id (-> action :transfer/from :db/id))
        to (find-by trade-db :trader/id (-> action :transfer/to :db/id))]
    (trading/trade trade-conn from to amount)))


