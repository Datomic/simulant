(use 'datomic.sim.repl)
(convenient)

(def sim-conn (scratch-conn))
(def sim-schema (-> "datomic-sim/schema.dtm" io/resource slurp read-string))

(doseq [k [:core :model :test :action :sim :process]]
  (doseq [tx (get sim-schema k)]
    (transact sim-conn tx)))

;; model ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def model-schema
  [{:db/id #db/id[:db.part/db]
    :db/ident :model.type/helloWorld
    :db/doc "A degenerate trading system with a single type of trader,
tracking only balances."}
   {:db/id #db/id[:db.part/db]
    :db/ident :helloWorld/traderCount
    :db/valueType :db.type/long
    :db/doc "Number of traders"
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}
   {:db/id #db/id[:db.part/db]
    :db/ident :helloWorld/initialBalance
    :db/valueType :db.type/long
    :db/doc "Initial balance for each trader, in arbitrary 'points'."
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}
   {:db/id #db/id[:db.part/db]
    :db/ident :helloWorld/meanTradeAmount
    :db/valueType :db.type/long
    :db/doc "Mean size of trades (geometric distribution)."
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}   
   {:db/id #db/id[:db.part/db]
    :db/ident :helloWorld/meanTradeFrequency
    :db/valueType :db.type/long
    :db/doc "Mean frequency of trades in hours (geometric distribution)"
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}])

(transact sim-conn model-schema)

(defn hours->msec [h] (* h 60 60 1000))

(def model-id (tempid :model))
(def model-data
  [{:db/id model-id
    :model/type :model.type/helloWorld
    :model/duration (hours->msec 8)
    :helloWorld/traderCount 100
    :helloWorld/initialBalance 1000
    :helloWorld/meanTradeAmount 100
    :helloWorld/meanTradeFrequency 1}])

(def model
  (-> @(transact sim-conn model-data)
      (txresult-entity model-id)))

(def test-schema
  [{:db/id #db/id[:db.part/db]
    :db/ident :helloWorld/trade
    :db/doc "Action type"}
   {:db/id #db/id[:db.part/db]
    :db/ident :transfer/from
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}
   {:db/id #db/id[:db.part/db]
    :db/ident :transfer/to
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}
   {:db/id #db/id[:db.part/db]
    :db/ident :transfer/amount
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}])

(transact sim-conn test-schema)

(defn generate-trade-action-data
  "Generate a trade from trader ord, based on the model"
  [model ord at-time]
  [[{:db/id (tempid :test)
     :action/atTime at-time
     :action/type :helloWorld/trade
     :transfer/from ord
     :transfer/to (gen/uniform 0 (:helloWorld/traderCount model))
     :transfer/amount (long (gen/geometric (/ 1 (:helloWorld/meanTradeAmount model))))}]])

(generate-trade-action-data model 0 0)

(defn generate-trader-actions-data
  "Generate all actions for trader ord, based on model"
  [model ord]
  (let [limit (:model/duration model)
        step #(gen/geometric (/ 1 (hours->msec (:helloWorld/meanTradeFrequency model))))]
    (->> (reductions + (repeatedly step))
         (take-while (fn [t] (< t limit)))
         (mapcat #(generate-trade-action-data model ord %)))))

(defmethod generate-test-data :model.type/helloWorld
  [model]
  (mapcat
   (fn [ord] (generate-trader-actions-data model ord))
   (range (:helloWorld/traderCount model))))

(generate-test-data model)
(create-test sim-conn model)

(count-by (db sim-conn) :transfer/from)


