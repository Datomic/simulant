(use 'datomic.sim.repl)
(convenient)

(def sim-uri (str "datomic:mem://" (squuid)))

(def sim-conn (reset-conn sim-uri))
(def sim-schema (-> "datomic-sim/schema.dtm" io/resource slurp read-string))
(def hello-schema (-> "datomic-sim/hello-world.dtm" io/resource slurp read-string))

(doseq [k [:core :model :test :agent :action :clock :sim :process :log]]
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

(def hello-test (sim/create-test sim-conn model
                                 {:db/id (tempid :test)
                                  :test/duration (hours->msec 4)}))

(def hello-sim (sim/create-sim sim-conn hello-test {:db/id (tempid :sim)
                                                    :sim/systemURI (str "datomic:mem://" (squuid))
                                                    :sim/processCount 2}))

(def hello-clock (sim/create-fixed-clock sim-conn hello-sim {:clock/multiplier 480}))

(def prun (sim/run-sim-process sim-uri (:db/id hello-sim)))

;; if you wanted to stop
(future-cancel (:runner prun))
(sim/clock-elapsed-time (-> (:process prun) :sim/_processes first :sim/clock))
(sim/sleep-until (-> process :sim/_processes first :sim/clock) 287442)

(find-all-by (db sim-conn) :log/process)

(sim/process-agents (:process prun))







