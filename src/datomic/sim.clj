;; ## Simulation Testing
;;
;; ## Coding Style
;;
;; 1. Functions prefixed 'create-' perform transactions.
;; 2. Functions prefixed 'generate-' make data.
;; 3. Use multimethods, not types, for polymorphism. This lets
;;    programs work directly with database data, with no
;;    data-object translation.

(ns datomic.sim
  (:use datomic.sim.util)
  (:require [clojure.java.io :as io]
            [datomic.api :as d])
  (:import [java.util.concurrent Executors]))

(set! *warn-on-reflection* true)

;; ## Core Methods
;;
;; Specialize these multimethods when creating a sim

(defmulti create-test
  "Execute a series of transactions against conn that create a
   test based on model."
  (fn [conn model test] (getx model :model/type)))

(defmulti create-sim
  "Execute a series of transactions against conn that create a
   sim based on test."
  (fn [conn test sim] (getx test :test/type)))

(defmulti perform-action
  "Perform an action."
  (fn [action process] (getx action :action/type)))

(defmulti lifecycle
  "Return Lifecycle protocol implementation associated with
   entity. Defaults to no-op."
  (fn [entity] (get entity :lifecycle/type)))

;; ## Resource Lifecycle
;;
;; Ignore this, likely to be removed

(def resources-ref (atom nil))

(defn resources
  "Return the resources associated with this process"
  [process]
  (get @resources-ref (getx process :db/id)))

(defprotocol Lifecycle
  (setup [_ conn entity] "Returns resources map which will be available to teardown.")
  (teardown [_ conn entity resources]))

(defmethod lifecycle :default
  [entity]
  (reify Lifecycle
         (setup [this conn entity])
         (teardown [this conn entity resources])))

;; ## Helper Functions

(defn construct-basic-sim
  "Construct a basic sim that assigns agents round-robin to
   n symmetric processes."
  [test sim]
  (require-keys sim :db/id :sim/processCount)
  [(assoc sim
     :sim/type :sim.type/basic
     :test/_sims (e test))])

;; ## Infrequently Overridden
;;
;; Many sims can use built-in implementation of these.

(defmulti join-sim
  "Returns a process entity or nil if could not join. Override
   this if you want to allocate processes to a sim asymmetrically."
  (fn [conn sim process] (getx sim :sim/type)))

(defmulti process-agents
  "Given a process that has joined a sim, return that process's
   agents"
  (fn [process] (getx process :process/type)))

(defmulti start-clock
  "Start the sim clock, returns the updated clock"
  (fn [conn clock] (getx clock :clock/type)))

(defmulti sleep-until
  "Sleep until sim clock reaches clock-elapsed-time"
  (fn [clock elapsed] (getx clock :clock/type)))

(defmulti clock-elapsed-time
  "Return the elapsed simulation time, in msec"
  (fn [clock] (getx clock :clock/type)))

;; ## Processes
(def ^:private default-executor
     "Default executor used by sim agents. This is important because
      Clojure's send executor would not allow enough concurrency,
      and the send-off executor would create too many threads."
  (delay
   (Executors/newFixedThreadPool 50)))

(def ^:private process-executor
     "Executor currently in use by sim."
  (atom nil))

(defn start-sim
  "Ensure sim is ready to begin, join process to it, return process.
   Sets the executor service used by sim actors."
  ([conn sim process]
     (start-sim conn sim process @default-executor))
  ([conn sim process executor]
     (reset! process-executor executor)
     (start-clock conn (getx sim :sim/clock))
     (join-sim conn sim process)))

(defmethod join-sim :default
  [conn sim process]
  (let [id (getx process :db/id)
        ptype (keyword "process.type" (name (:sim/type sim)))]
    (-> @(d/transact conn [[:sim/join (e sim) (assoc process
                                                :process/type ptype
                                                :process/state :process.state/running
                                                :process/uuid (d/squuid))]])
        (tx-ent id))))

;; The default implementation of process-agents assumes that all
;; processes in the sim should have agents allocated round-robin
;; across them.  
(defmethod process-agents :default
  [process]
  (let [sim (-> process :sim/_processes only)
        test (-> sim :test/_sims only)
        nprocs (:sim/processCount sim)
        ord (:process/ordinal process)]
    (->> (:test/agents test)
         (sort-by :db/id)
         (keep-partition ord nprocs))))

(defn action-seq
  "Create a lazy sequence of actions for agents, which should be
    the subset of agents that this process represents. The use of
    the datoms API instead of query is important, as the number
    of actions could be huge."
  [db agents]
  (let [agent-ids (->> agents
                       (map :db/id)
                       (into #{}))]
    (->> (d/datoms db :avet :action/atTime)
         (map (fn [datom] (d/entity db (:e datom))))
         (filter (fn [action] (contains? agent-ids (-> action :agent/_actions only :db/id)))))))

;; ## Fixed Clock

(defmethod start-clock :clock.type/fixed
  [conn clock]
  (let [t (System/currentTimeMillis)
        {:keys [db-after]} @(d/transact conn [[:deliver (e clock) :clock/realStart t]])]
    (d/entity db-after (e clock))))

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
      (Thread/sleep (long (/ (- twhen tnow) (getx clock :clock/multiplier)))))))

(defn construct-fixed-clock
  "Generates transaction data to create a fixed clock."
  [sim clock]
  (require-keys clock :db/id :clock/multiplier)
  (let [id (:db/id clock)]
    [(assoc (update-in clock [:clock/multiplier] double)
       :clock/type :clock.type/fixed)
     [:db.fn/cas (e sim) :sim/clock nil id]]))

(defn create-fixed-clock
  "Returns clock. Clock passed in must have :clock/multiplier"
  [conn sim clock]
  (let [id (get clock :db/id (d/tempid :sim))]
    (-> @(d/transact conn (construct-fixed-clock sim (assoc clock :db/id id)))
        (tx-ent id))))

;; ## Process Runner
(defn report-agent-error
  "What to do locally when an agent's action barfs an exception.
   This is likely only to be useful during development, with a human
   watching at the REPL. In a distributed run of a sim, the error
   will be detectable "
  [sim-agent agent ^Throwable error]
  (.printStackTrace error))

(def ^:private via-agent-for
     "Cache of one Clojure agent per sim agent. One could almost
      imagine that this is what Clojure agents were designed for."
  (memoize
   (fn [sim-agent]
     (assert sim-agent)
     (let [a (agent nil)]
       (set-error-handler! a (partial report-agent-error sim-agent))
       (set-error-mode! a :fail)
       a))))

(defn feed-action
  "Feed a single action to the actor's agent."
  [action process]
  (let [actor (-> (getx action :agent/_actions) only)]
    (send-via
     @process-executor
     (via-agent-for actor)
     (fn [agent-state]
       (perform-action action process)
       agent-state))))

(defn feed-all-actions
  "Feed all actions, which are presumed to be sorted by ascending
   :action/atTime"
  [process actions]
  (let [sim (-> process :sim/_processes only)
        clock (getx sim :sim/clock)]
    (doseq [{elapsed :action/atTime :as action} actions]
      (sleep-until clock elapsed)
      (feed-action action process))))

(defn await-all
  "Given a collection of objects, calls await on the agent for each one"
  [coll]
  (apply await (map via-agent-for coll)))

(defn process-loop
  "Run the control loop for a process. Dispatches all agent actions,
   and returns a future you can use to wait for the sim to complete."
  [sim-conn process]
  (logged-future
   (let [lifecycle (-> process :process/resource-manager lifecycle)
         resources (setup lifecycle sim-conn process)
         agents (process-agents process)
         actions (action-seq (d/db sim-conn) agents)]
     (swap! resources-ref assoc (:db/id process) resources)
     (try
      (feed-all-actions process actions)
      (catch Throwable t
        (.printStackTrace t)
        (d/transact sim-conn [{:db/id (:db/id process)
                               :process/state :process.state/failed
                               :process/errorDescription (stack-trace-string t)}])
        (throw t))
      (finally
       (await-all agents)
       (swap! resources-ref dissoc (:db/id process))))
     (teardown lifecycle sim-conn process resources)
     (d/transact sim-conn [[:db/add (:db/id process) :process/state :process.state/completed]]))))

;; ## API Entry Points

(defn run-sim-process
  "Backgrounds process loop and returns process object. Returns map
   with keys :process and :runner (a future), or nil if unable to run sim"
  [sim-uri sim-id]
  (let [sim-conn (d/connect sim-uri)
        sim (d/entity (d/db sim-conn) sim-id)]
    (when-let [process (start-sim sim-conn sim {:db/id (d/tempid :sim)})]
      (let [fut (process-loop sim-conn process)]
        {:process process :runner fut}))))

(defn -main
  "Command line entry point for running a sim process. Takes the URI of a
   simulation database, and the entity id of the sim to be run, both as
   strings."
  [sim-uri sim-id]
  (if-let [process (run-sim-process sim-uri (safe-read-string sim-id))]
    (println "Joined sim " sim-id " as process " (:db/id process))
    (println "Unable to join sim " sim-id)))



