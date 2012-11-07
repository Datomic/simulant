(ns datomic.sim
  (:use datomic.api datomic.sim.util))

(set! *warn-on-reflection* true)

;; generate model & create-model have no multimethods

(defmulti create-test
  "Execute a series of transactions agaist conn that create a
   test based on model. Default implementation calls generate-test
   to generate the transactions, and then batches and submits
   them. Returns test entity."
  (fn [conn model test] (:model/type model)))

(defmulti generate-test
  "Generate a series of transactions that constitute a test
   based on model"
  (fn [model test] (:model/type model)))

(defmulti create-sim
  "Execute a series of transactions agaist conn that create a
   sim based on test. Default implementation calls generate-sim
   to generate the transactions, and then submits them"
  (fn [conn test sim] (:test/type test)))

(defmulti generate-sim
  "Generate a series of transactions that constitute a sim
   based on test, plus a map of sim configuration data."
  (fn [test sim] (:test/type test)))

(defmulti join-sim
  "Returns a process entity or nil if could not join"
  (fn [conn sim process] (:sim/type sim)))

(defmulti process-agents
  "Given a process that has joined a sim, return that process's
   agents"
  (fn [process] (:process/type process)))

(defmulti perform-action
  "Perform an action"
  (fn [action sim] (:action/type action)))

(defmulti start-clock
  "Start the sim clock, returns the updated clock"
  (fn [conn clock] (:clock/type clock)))

(defmulti sleep-until
  "Sleep until sim clock reaches clock-elapsed-time"
  (fn [clock elapsed] (:clock/type clock)))

(defmulti clock-elapsed-time
  "Return the elapsed simulation time, in msec"
  (fn [clock] (:clock/type clock)))

;; models ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; tests ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod create-test :default
  [conn model test]
  (let [txes (generate-test model test)]
    (transact-batch conn txes 1000)))

;; sim ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod create-sim :default
  [conn test sim]
  (let [tx (generate-sim test sim)]
    (-> @(transact conn tx)
        (tx-ent (:db/id sim)))))

(defmethod generate-sim :default
  [test sim]
  (require-keys sim :db/id :sim/processCount)
  [(assoc sim
     :sim/type :sim.type/basic
     :test/_sims (e test))])

;; processes ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod join-sim :sim.type/basic
  [conn sim process]
  (let [id (getx process :db/id)]
    (-> @(transact conn [[:sim/join (e sim) process]
                         [:db/add id :process/type :process.type/basic]])
        (tx-ent id))))

(defmethod process-agents :process.type/basic
  [process]
  (let [sim (-> process :sim/_processes solo)
        test (-> sim :test/_sims solo)
        nprocs (:sim/processCount sim)
        ord (:process/ordinal process)]
    (->> (:test/agents test)
         (sort-by :db/id)
         (keep-partition ord nprocs))))

(defn action-seq
  "Returns lazy, time-ordered seq of actions for this process."
  [db process]
  (let [test (-> process :sim/_processes first :test/_sims first)
        agent-ids (->> (process-agents process)
                       (map :db/id)
                       (into #{}))]
    (->> (datoms db :avet :action/atTime)
         (map (fn [datom] (entity db (:e datom))))
         (filter (fn [action] (contains? agent-ids (-> action :agent/_actions solo :db/id)))))))

;; sim time (fixed clock) ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod start-clock :clock.type/fixed
  [conn clock]
  (let [t (System/currentTimeMillis)
        {:keys [db-after]} @(transact conn [[:db.fn/cas (e clock) :clock/realStart nil t]])]
    (entity db-after (e clock))))

(defmethod clock-elapsed-time :clock.type/fixed
  [clock]
  (let [start (getx clock :clock/realStart)
        mult (getx clock :clock/multiplier)
        real-elapsed (- (System/currentTimeMillis) start)]
    (long (* real-elapsed mult))))

(defn sleep-until
  [clock twhen]
  (let [tnow (clock-elapsed-time clock)]
    (when (< tnow twhen)
      (Thread/sleep (long (* (getx clock :clock/multiplier) (- twhen tnow)))))))

;; sim runner ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn handle-action-error
  [actor agent ^Throwable error]
  (.printStackTrace error))

(def ^:private via-agent-for
  (memoize
   (fn [x]
     (assert x)
     (let [a (agent nil)]
       (set-error-handler! a (partial handle-action-error x))
       (set-error-mode! a :continue)
       a))))

(defn feed-action
  "Feed a single action to the actor's agent."
  [{actor :sim/_actor :as action}]
  (send-off
   (via-agent-for (:db/id actor))
   (fn [agent-state]
     (perform-action action)
     agent-state)))

(defn feed-all
  "Feed all actions, which should be sorted by ascending
   :action/atTime"
  [actions]
  (doseq [{t :action/atTime :as action} actions]
    (sleep-until t)
    (feed-action action)))

(defn await-all
  "Given a collection of objects, calls await on the agent for each one"
  [coll]
  (apply await (map via-agent-for coll)))

(defn -main
  [sim-uri sim-id]
  (let [sim-conn (connect sim-uri)
        sim (entity (db sim-conn) (safe-read-string sim-id))]
    (if-let [process (join-sim sim-conn sim {:db/id (tempid :sim)})]
      (println "Joined sim " sim-id " as process " (:db/id process))
      (do
        (println "Unable to join sim " sim-id)
        (System/exit -1)))))
