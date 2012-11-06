(use 'datomic.sim.repl)
(convenient)

(def sim-conn (scratch-conn))
(def sim-schema (-> "datomic-sim/schema.dtm" io/resource slurp read-string))
(def hello-schema (-> "datomic-sim/hello-world.dtm" io/resource slurp read-string))

(doseq [k [:core :model :test :agent :action :sim :process]]
  (doseq [tx (get sim-schema k)]
    (transact sim-conn tx)))

(doseq [k [:model :test]]
  (doseq [tx (get hello-schema k)]
    (transact sim-conn tx)))

(def model-id (tempid :model))
(def model-data
  [{:db/id model-id
    :model/type :model.type/helloWorld
    :model/traderCount 100
    :model/initialBalance 1000
    :model/meanTradeAmount 100
    :model/meanTradeFrequency 1}])

(def model
  (-> @(transact sim-conn model-data)
      (tx-ent model-id)))

(defn create-hello-world-test
  "Returns test entity"
  [conn model id duration]
  (-> @(transact conn [{:db/id id
                        :test/type :test.type/helloWorld
                        :test/duration duration
                        :model/_tests (e model)}])
      (tx-ent id)))

(def hello-test (create-hello-world-test sim-conn model (tempid :test) (hours->msec 8)))

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

(def traders
  (create-hello-world-traders sim-conn hello-test))

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

(generate-trade hello-test (first traders) traders 10)

(defn generate-trader-trades
  "Generate all actions for trader ord, based on model"
  [test from-trader traders]
  (let [model (-> test :model/_tests first)
        limit (:test/duration test)
        step #(gen/geometric (/ 1 (hours->msec (:model/meanTradeFrequency model))))]
    (->> (reductions + (repeatedly step))
         (take-while (fn [t] (< t limit)))
         (mapcat #(generate-trade test from-trader traders %)))))

(generate-trader-trades hello-test (first traders) traders)

(defn generate-all-trades
  [test traders]
  (mapcat
   (fn [from-trader] (generate-trader-trades test from-trader traders))
   traders))

(count (generate-all-trades hello-test traders))

(defmethod sim/create-test :model.type/helloWorld
  [conn model]
  (let [test (create-hello-world-test conn model (tempid :test) (hours->msec 8))
        traders (create-hello-world-traders conn test)]
    (transact-batch conn (generate-all-trades test traders) 1000)
    (entity (db conn) (e test))))

(def hello-test (sim/create-test sim-conn model))

(count-by (db sim-conn) :transfer/to)


