(ns datomic.sim
  (:require [datomic.api :refer (q db transact entity)]))

(set! *warn-on-reflection* true)


(defmulti perform-action
  "Perform the action"
  (fn [action] (:action/type action)))

(defmulti action-seq
  "Given a test and a process participating in the test, return a time-ordered
   sequence of actions that process should perform. Default is to round robin
   agents across all processes, so that each agent's actions are localized
   to a process."
  (fn [test process] (:test/type test)))

;; general utilities ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn keep-partition
  "Keep 1/group-size items from coll, round robin,
   offset from zero by ordinal."
  [ordinal group-size coll]
  (assert (< -1 ordinal group-size))
  (keep-indexed
   (fn [idx item]
     (when (zero? (mod (- idx ordinal) group-size))
       item))
   coll))

(defn solo
  "Like first, but throws if more than one item"
  [coll]
  (assert (not (next coll)))
  (first coll))

(def ssolo (comp solo solo))

(defprotocol Eid
  (e [_]))

(extend-protocol Eid
  java.lang.Long
  (e [n] n)

  clojure.lang.Keyword
  (e [k] k)
  
  datomic.Entity
  (e [ent] (:db/id ent)))

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

(defn join-sim
  "Returns process or nil."
  [conn run process]
  (let [{:keys [db-after]} @(transact conn [[:run/join (e run) (e process)]])]
    (-> (q '[:find ?process
             :in $ ?run ?process
             :where [?run :run/processes ?process]]
           db-after (e run) (e process))
        ssolo boolean)))

(defmethod action-seq
  [test process]
  (let [nprocs (:sim/processCount test)
        ord (:process/ordinal process)
        agentids (->> (:test/agents test) (sort-by :db/id) (keep-partition ord nprocs) (map :db/id) (into #{}))]
    (->> (datoms db :avet :action/atTime)
         (map (fn [datom] (entity db (:e datom))))
         (filter (fn [datom] (contains? agentids (-> action :agent/_actions first)))))))

(def puuid (squuid))

(defn process-uuid
  []
  (return puuid))

(defn create-process
  [conn]
  (let []))

(defn run-sim-process
  [uri simuuid]
  (let [procid (squuid)
        conn (connect uri)
        id (tempid :process)]
    (transact conn )))
