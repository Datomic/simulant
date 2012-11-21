(use 'datomic.sim.repl)
(convenient)
(use 'datomic.examples.trading-sim)
(require '[datomic.examples.trading :as trading])

(def sim-uri (str "datomic:mem://" (squuid)))

(def sim-conn (reset-conn sim-uri))

;; generic simulation schema
(load-schema sim-conn "datomic-sim/schema.dtm")

;; schema for this specific sim
(load-schema sim-conn "datomic-sim/trading-sim.dtm")

;; schema for system under test (reused by sim)
(load-schema sim-conn "datomic-sim/trading.dtm")

;; model for this sim
(def model-id (tempid :model))
(def trading-model-data
  [{:db/id model-id
    :model/type :model.type/trading
    :model/traderCount 100
    :model/meanTradeAmount 100
    :model/initialBalance 1000
    :model/meanHoursBetweenTrades 1}])
(def trading-model
  (-> @(transact sim-conn trading-model-data)
      (tx-ent model-id)))

;; activity for this sim
(def trading-test (sim/create-test sim-conn trading-model
                                 {:db/id (tempid :test)
                                  :test/duration (hours->msec 4)}))


;; sim
(def trading-sim (sim/create-sim sim-conn trading-test {:db/id (tempid :sim)
                                                        :sim/systemURI (str "datomic:mem://" (squuid))
                                                      :sim/processCount 10}))

(defn assoc-codebase-tx [entities]
  (let [codebase (gen-codebase)
        cid (:db/id codebase)]
    (cons
     codebase
     (mapv #(assoc {:db/id (:db/id %)} :source/codebase cid) entities))))
(transact sim-conn (assoc-codebase-tx [trading-test trading-sim]))

;; clock for this sim
(def sim-clock (sim/create-fixed-clock sim-conn trading-sim {:clock/multiplier 480}))

;; run the processes for this sim
(def pruns
  (->> #(sim/run-sim-process sim-uri (:db/id trading-sim))
       (repeatedly (:sim/processCount trading-sim))
       (into [])))

;; wait for sim to finish
(time
 (mapv (fn [prun] @(:runner prun)) pruns))

;; grab latest database values so we can validate each of the steps above
(def simdb (db sim-conn))
(def traderdb (db (connect (:sim/systemURI trading-sim))))

;; make sure count of actions seems reasonable
(def actions
  (->> (q '[:find ?action
            :in $ ?test
            :where
            [?test :test/agents ?agent]
            [?agent :agent/actions ?action]]
          simdb (:db/id trading-test))
       (map first)))
(count actions)

;; check the actions
(def action-amounts
  (->> (q '[:find ?amount ?action
            :in $ ?test
            :where
            [?test :test/agents ?agent]
            [?agent :agent/actions ?action]
            [?action :action/type :action.type/trade]
            [?action :transfer/amount ?amount]]
          simdb (:db/id trading-test))
       (map first)))
(count action-amounts)
(mean action-amounts)

;; check the trades
(def trade-amounts
  (->> (q '[:find ?amount ?tx
            :in $ ?test
            :where
            [?tx :transfer/amount ?amount]
            [?tx :db/txInstant]]
          traderdb (:db/id trading-test))
       (map first)))
(count trade-amounts)
(mean trade-amounts)

;; check the traders and their balances
(def trader-ids (find-all-by traderdb :trader/id))
(count trader-ids)

(def trader-balances
  (->> trader-ids
       (map (fn [[e]] (:db/id e)))
       (map (partial trading/balance traderdb))))
(apply + trader-balances)

;; sim written in hopes that balances will not go negative
;; but they might, because system under test does not check!
(filter neg? trader-balances)
