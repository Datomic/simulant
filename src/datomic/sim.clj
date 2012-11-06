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

(defmulti process-agentids
  "Given a process that has joined a sim, return the agent
   ids that process represents."
  (fn [process] (:process/type)))

(defmulti perform-action
  "Perform an action"
  (fn [action] (:action/type action)))


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

;; processes join ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#_(defmethod join-sim :sim/basic
  [conn sim process]
  (let [{:keys [db-after]} @(transact conn [[:sim/join (e sim) process-uuid]])]
    (-> (q '[:find ?process
             :in $ ?run ?process
             :where [?run :run/processes ?process]]
           db-after (e run) (e process))
        ssolo)))

;; sim time ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def sim-start (atom nil))

(defn reset
  "Zero the sim clock. You must call this before running
   a simulation!"
  []
  (reset! sim-start (System/currentTimeMillis)))

(defn now
  "Returns the current simulation time.  This is the naive version using the
  system time in ms."
  []
  (- (System/currentTimeMillis) @sim-start))

(defn sleep-until
  "Checks if the target time is less-than the actual time and sleeps the remaining ms
  if it is."
  [twhen]
  (let [tnow (now)]
    (when (< tnow twhen)
      (Thread/sleep (- twhen tnow)))))


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

(defn action-seq
  [test process]
  (let [nprocs (:sim/processCount test)
        ord (:process/ordinal process)
        agentids (->> (:test/agents test)
                      (sort-by :db/id)
                      (keep-partition ord nprocs)
                      (map :db/id)
                      (into #{}))]
    (->> (datoms db :avet :action/atTime)
         (map (fn [datom] (entity db (:e datom))))
         (filter (fn [action] (contains? agentids (-> action :agent/_actions first)))))))

(def puuid (squuid))

(defn process-uuid
  []
  puuid)

(defn run-sim-process
  [uri simuuid]
  (let [procid (squuid)
        conn (connect uri)
        id (tempid :process)]
    (transact conn )))
