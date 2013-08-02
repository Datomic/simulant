;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(use 'simulant.examples.repl)
(convenient)

(require
 '[simulant.examples.trading :as trading]
 '[simulant.examples.trading-sim :as tsim])

(def sim-uri "datomic:free://localhost:4334/sim")
(d/create-database sim-uri)
(def sim-conn (d/connect sim-uri))

(comment 
  ;; generic simulation schema
  (load-schema sim-conn "simulant/schema.edn")

  ;; schema for this specific sim
  (load-schema sim-conn "simulant/examples/trading-sim.edn")

  ;; schema for system under test (reused by sim)
  (load-schema sim-conn "simulant/examples/trading.edn")
  )

(def codebase-id (d/tempid :model))
(def codebase
  (-> @(d/transact sim-conn [{:db/id codebase-id
                              :repo/type :repo.type/git
                              :git/uri (git-repo-uri)
                              :git/sha (git-latest-sha)}])
      (tx-ent codebase-id)))

;; create the model (first time only)
(comment
  (def model-id (d/tempid :model))
  (def trading-model-data
    {:model/type :model.type/trading
     :model/traderCount 100
     :model/meanTradeAmount 100
     :model/initialBalance 1000
     :model/meanHoursBetweenTrades 1
     :source/codebase (:db/id codebase)
     :db/ident :tradingExample/model})
  (def trading-model
    (-> @(d/transact sim-conn [(assoc trading-model-data :db/id model-id)])
        (tx-ent model-id)))
  )

;; find the model (subsequent runs)
(def trading-model (-> (d/entity (d/db sim-conn) :tradingExample/model)
                       d/touch))

;; create a test associated with the model (first time only)
(comment
  (def trading-test
    (sim/create-test sim-conn trading-model
                     {:db/id (d/tempid :test)
                      :db/ident :tradingExample/test
                      :source/codebase (:db/id codebase)
                      :test/duration (hours->msec 4)}))
  )

;; find the test (subsequent runs)
(def trading-test (-> (d/entity (d/db sim-conn) :tradingExample/test)
                      d/touch))

;; sim
(def trading-sim
  (sim/create-sim
   sim-conn
   trading-test
   {:db/id (d/tempid :sim)
    :source/codebase (:db/id codebase)
    :sim/systemURI (str "datomic:free://localhost:4334/" (System/currentTimeMillis))
    :sim/processCount 10}))

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
(d/q '[:find (count ?amount) (avg ?amount)
       :with ?action
       :in $ ?test
       :where
       [?test :test/agents ?agent]
       [?agent :agent/actions ?action]
       [?action :action/type :action.type/trade]
       [?action :transfer/amount ?amount]]
       simdb (:db/id trading-test))

;; check the trades
(d/q '[:find (count ?amount) (avg ?amount)
       :with ?tx
       :in $ ?test
       :where
       [?tx :transfer/amount ?amount]
       [?tx :db/txInstant]]
     traderdb (:db/id trading-test))

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

(defn procs-completed?
  [db sim-id]
  (let [sim (d/entity db sim-id)]
    (and (=
          (count (:sim/processes sim))
          (:sim/processCount sim))
         (every? #(= :process.state/completed
                     (:process/state %))
                 (:sim/processes sim)))))

(defn latest-proc-completion
  [db sim-id]
  (->> (d/q '[:find (max ?inst)
              :with ?proc
              :in $ ?sim
              :where
              [?sim :sim/processes ?proc]
              [?proc :process/state :process.state/completed ?tx]
              [?tx :db/txInstant ?inst]]
            db sim-id)
       ffirst))

(def rules
  '[[[actionTime ?sim ?actionType ?action ?nsec]
     [?test :test/sims ?sim]
     [?test :test/agents ?agent]
     [?agent :agent/actions ?action]
     [?action :action/type ?actionType]
     [?log :actionLog/action ?action]
     [?log :actionLog/sim ?sim]
     [?log :actionLog/nsec ?nsec]]
    [[procsCompleted ?e]
     [?e :sim/processes]
     [(user/procs-completed? $ ?e)]]
    [[simCompletedAt ?sim ?inst]
     (procsCompleted ?sim)
     [(user/latest-proc-completion $ ?sim) ?inst]]])

;; count of trade times should match count of trades
(d/q '[:find (count ?nsec)
       :with ?action
       :in $ % ?sim ?action-type
       :where (actionTime ?sim ?action-type ?action ?nsec)]
     simdb rules (:db/id trading-sim) :action.type/trade)

(defn ns->ms-avg
  "Returns the msec average of a set of nsec times."
  [nses]
  (/ (reduce + 0 nses) (double (count nses)) 1000 1000))

;; mean trade time for this sim, in msec
(d/q '[:find (user/ns->ms-avg ?nsec)
       :with ?action
       :in $ % ?sim ?action-type
       :where (actionTime ?sim ?action-type ?action ?nsec)]
     simdb rules (:db/id trading-sim) :action.type/trade)

;; find all completed sims
(d/q '[:find ?e
       :in $1 %
       :where ($1 procsCompleted ?e)]
     simdb rules)

;; history of trade times across sim runs
(d/q '[:find ?e (user/ns->ms-avg ?nsec) ?inst
       :with ?action
       :in $ % ?action-type
       :where (simCompletedAt ?e ?inst)
              (actionTime ?sim ?action-type ?action ?nsec)]
     simdb rules :action.type/trade)




