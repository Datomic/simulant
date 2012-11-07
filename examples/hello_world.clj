(use 'datomic.sim.repl)
(convenient)

(def sim-conn (scratch-conn))
(def sim-schema (-> "datomic-sim/schema.dtm" io/resource slurp read-string))
(def hello-schema (-> "datomic-sim/hello-world.dtm" io/resource slurp read-string))

(doseq [k [:core :model :test :agent :action :clock :sim :process]]
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

(def hello-test (create-hello-world-test sim-conn model
                                         {:db/id (tempid :test)
                                          :test/duration (hours->msec 8)}))

(def traders
  (create-hello-world-traders sim-conn hello-test))

(generate-trade hello-test (first traders) traders 10)

(generate-trader-trades hello-test (first traders) traders)

(count (generate-all-trades hello-test traders))

(def hello-test (sim/create-test sim-conn model {:db/id (tempid :test)
                                                 :test/duration (hours->msec 8)}))

(def hello-sim (sim/create-sim sim-conn hello-test {:db/id (tempid :sim)
                                                    :sim/datomicURI (str "datomic:mem://" (squuid))
                                                    :sim/processCount 2}))

(set! *print-length* 20)
(map
 (fn [[e]] (:trader/balance e))
 (find-all-by (db trade-conn) :trader/balance))

(def trade-conn (connect (:sim/datomicURI hello-sim)))


(def proc1 (sim/join-sim sim-conn hello-sim {:db/id (tempid :sim)}))
(def proc2 (sim/join-sim sim-conn hello-sim {:db/id (tempid :sim)}))

(def act1 (first (sim/action-seq (db sim-conn) proc1)))

(sim/perform-action act1 hello-sim)

(->> (datoms (db sim-conn) :avet :action/type)
     seq)

(require '[clojure.set :as set])
(set/intersection
 (sim/process-agents proc1)
 (sim/process-agents proc2))

(=
 (:test/agents hello-test)
 (set/union
  (sim/process-agents proc1)
  (sim/process-agents proc2)))
