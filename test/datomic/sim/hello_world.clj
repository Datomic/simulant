(ns datomic.sim.hello-world
  (:use datomic.api datomic.sim.util)
  (:require [clojure.test.generative.generators :as gen]
            [datomic.sim :as sim]))

(defn create-hello-world-test
  "Returns test entity"
  [conn model test]
  (require-keys test :db/id :test/duration)
  (-> @(transact conn [(assoc test
                         :test/type :test.type/helloWorld
                         :model/_tests (e model))])
      (tx-ent (:db/id test))))

(defn create-hello-world-traders
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
        step #(gen/geometric (/ 1 (hours->msec (:model/meanTradeFrequency model))))]
    (->> (reductions + (repeatedly step))
         (take-while (fn [t] (< t limit)))
         (mapcat #(generate-trade test from-trader traders %)))))

(defn generate-all-trades
  [test traders]
  (mapcat
   (fn [from-trader] (generate-trader-trades test from-trader traders))
   traders))

(defmethod sim/create-test :model.type/helloWorld
  [conn model test]
  (let [test (create-hello-world-test conn model test)
        traders (create-hello-world-traders conn test)]
    (transact-batch conn (generate-all-trades test traders) 1000)
    (entity (db conn) (e test))))




