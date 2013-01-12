;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(use 'simulant.examples.repl)
(convenient)
(use 'simulant.examples.trading-sim)
(require '[simulant.examples.trading :as trading])

(def sim-uri (str "datomic:mem://" (d/squuid)))

(def sim-conn (reset-conn sim-uri))

;; generic simulation schema
(load-schema sim-conn "simulant/schema.edn")

;; schema for this specific sim
(load-schema sim-conn "simulant/examples/trading-sim.edn")

;; schema for system under test (reused by sim)
(load-schema sim-conn "simulant/examples/trading.edn")

;; model for this sim
(def model-id (d/tempid :model))
(def trading-model-data
  [{:db/id model-id
    :model/type :model.type/trading
    :model/traderCount 100
    :model/meanTradeAmount 100
    :model/initialBalance 1000
    :model/meanHoursBetweenTrades 1}])
(def trading-model
  (-> @(d/transact sim-conn trading-model-data)
      (tx-ent model-id)))

;; activity for this sim
(def trading-test (sim/create-test sim-conn trading-model
                                 {:db/id (d/tempid :test)
                                  :test/duration (hours->msec 4)}))


;; sim
(def trading-sim (sim/create-sim sim-conn trading-test {:db/id (d/tempid :sim)
                                                        :sim/systemURI (str "datomic:mem://" (d/squuid))
                                                        :sim/processCount 10}))

;; codebase for the sim
(defn assoc-codebase-tx [entities]
  (let [codebase (gen-codebase)
        cid (:db/id codebase)]
    (cons
     codebase
     (mapv #(assoc {:db/id (:db/id %)} :source/codebase cid) entities))))
(d/transact sim-conn (assoc-codebase-tx [trading-test trading-sim]))

;; action log for this sim
(def action-log
  (sim/create-action-log sim-conn trading-sim))

;; clock for this sim
(def sim-clock (sim/create-fixed-clock sim-conn trading-sim {:clock/multiplier 960}))

;; run the processes for this sim
;; at scale each process would have its own box
(def pruns
  (->> #(sim/run-sim-process sim-uri (:db/id trading-sim))
       (repeatedly (:sim/processCount trading-sim))
       (into [])))

;; wait for sim to finish
(time
 (mapv (fn [prun] @(:runner prun)) pruns))

;; grab latest database values so we can validate each of the steps above
(def simdb (d/db sim-conn))
(def traderdb (d/db (d/connect (:sim/systemURI trading-sim))))

;; make sure count of actions seems reasonable
(def actions
  (->> (d/q '[:find ?action
              :in $ ?test
              :where
              [?test :test/agents ?agent]
              [?agent :agent/actions ?action]]
            simdb (:db/id trading-test))
       (map first)))
(count actions)

;; check the actions
(def action-amounts
  (->> (d/q '[:find ?amount ?action
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
  (->> (d/q '[:find ?amount ?tx
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
(assert (= 100000 (apply + trader-balances)))

;; sim written in hopes that balances will not go negative
;; but they might, because system under test does not check!
(filter neg? trader-balances)

;; test nonfunctional requirements, e.g. some metric on trade times
(def rules
  '[[[actionTime ?sim ?actionType ?action ?nsec]
     [?test :test/sims ?sim]
     [?test :test/agents ?agent]
     [?agent :agent/actions ?action]
     [?action :action/type ?actionType]
     [?log :actionLog/action ?action]
     [?log :actionLog/sim ?sim]
     [?log :actionLog/nsec ?nsec]]])

;; count of trade times should match count of trades
(assert (= (count actions)
           (count (d/q '[:find ?nsec
                         :with ?action
                         :in $ % ?sim ?action-type
                         :where (actionTime ?sim ?action-type ?action ?nsec)]
                       simdb rules (:db/id trading-sim) :action.type/trade))))

(def mean-trade-time-msec
  (-> (d/q '[:find (avg ?nsec)
             :with ?action
             :in $ % ?sim ?action-type
             :where (actionTime ?sim ?action-type ?action ?nsec)]
           simdb rules (:db/id trading-sim) :action.type/trade)
      ffirst
      (/ 1000 1000)))

;; This could make much more sophisticated use of statistics.
;; And, because the work is against a database, not a live
;; test, increased sophistication could be brought to bear
;; at any time.
(assert (< mean-trade-time-msec 20))


