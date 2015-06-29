;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

;; ## Simulation Testing
;;
;; ## Coding Style
;;
;; 1. Functions prefixed 'create-' perform transactions.
;; 2. Functions prefixed 'generate-' make data.
;; 3. Use multimethods, not types, for polymorphism. This lets
;;    programs work directly with database data, with no
;;    data-object translation.

(ns simulant.sim
  (:use simulant.util)
  (:require [clojure.java.io :as io]
            [datomic.api :as d])
  (:import
   [java.io Closeable File PushbackReader]
   [java.util.concurrent Executors]))

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

;; ## Services
;;
;; Services represent resources needed by a sim run.

(def ^:dynamic *services*
     "During a sim run, the *services* map contains a mapping
from fully qualified names to any services that the sim
need to use.")

(defn services
  "Return the service entities associated with this process"
  [process]
  (reduce into #{}
          [(-> process :sim/services)
           (-> process :sim/_processes only :sim/services)]))

(defprotocol Service
  "A service that is available during a simulation run.
   It has a lifecycle bounded by start-service and finalize-service.
   When a sim is run across multiple hosts, each Service will
   be instantiated on every host."
  (start-service [this process]
    "Start service, returning an object that will
     be stored in the *services* map, under the
     key specified by :service/key.")
  (finalize-service [this process]
    "Teardown service at the end of a sim run."))

(defn- create-service
  [conn svc-definition]
  (let [ctor (resolve (symbol (:service/constructor svc-definition)))]
    (assert ctor)
    (ctor conn svc-definition)))

(defn- start-services
  [conn process]
  (reduce
   (fn [m svc-definition]
     (assoc m (getx svc-definition :service/key)
            (start-service (create-service conn svc-definition) process)))
   {}
   (services process)))

(defn- finalize-services
  "Call teardown for each service associate with
process."
  [services-map process]
  (doseq [svc-instance (vals services-map)]
    (finalize-service svc-instance process)))

(defn with-services
  "Run f inside the service lifecycle for process."
  [conn process f]
  (let [services-map (start-services conn process)]
    (binding [*services* services-map]
      (try
       (f)
       (finally
        (let [process-after (d/entity (d/db conn) (e process))]
          (finalize-services services-map process-after)))))))

;; ## Action Log
;;
;; An Action Log keeps a list of transaction data describing actions
;; in a temporary file during a sim run, and then transacts that
;; data into the sim database at the completion of the run.
;;
;; ActionLogs implement IFn, and expect to be passed transaction data
(def ^:private serializer (agent nil))

(defrecord ActionLogService [conn ^File temp-file writer batch-size]
  Service
  (start-service [this process] this)

  (finalize-service [this process]
    (send-off serializer identity)
    (await serializer)
    (.close ^Closeable writer)
    (with-open [reader (io/reader temp-file)
                pbr (PushbackReader. reader)]
      (transact-pbatch conn (form-seq pbr) (or batch-size 1000))))

  clojure.lang.IFn
  (invoke
   [_ tx-data]
   (send-off
    serializer
    (fn [_]
      (binding [*out* writer
                *print-length* nil]
        (pr tx-data))
      nil))))

(defn construct-action-log
  [conn svc-definition]
    (let [f (File/createTempFile "actionLog" "edn")
          writer (io/writer f)]
      (->ActionLogService conn f writer (:actionLog.service/batch-size svc-definition))))

(defn create-action-log
  "Create an action log service for the sim. `attrs` is an optional
  map of attributes to include in the service definition."
  ([conn sim] (create-action-log conn sim {}))
  ([conn sim attrs]
   (let [id (d/tempid :sim)]
     (-> @(d/transact conn [(merge
                             {:db/id id
                              :sim/_services (e sim)
                              :service/type :service.type/actionLog
                              :service/constructor (str 'simulant.sim/construct-action-log)
                              :service/key :simulant.sim/actionLog}
                             attrs)])
       (tx-ent id)))))

;; ## Process state service

(defprotocol ConnectionVendor
  (connect [this]))

(defrecord ProcessStateService [uri]
  Service
  (start-service [this process]
    (d/create-database uri)
    this)

  (finalize-service [this process])

  ConnectionVendor
  (connect [this] (d/connect uri)))

(defn construct-process-state
  [_ _]
  (->ProcessStateService (str "datomic:mem://" (gensym))))

(defn create-process-state
  "Mark the fact that a sim will use a process state service."
  [conn sim]
  (let [id (d/tempid :sim)]
    (-> @(d/transact conn [{:db/id id
                            :sim/_services (e sim)
                            :service/type :service.type/processState
                            :service/constructor (str 'simulant.sim/construct-process-state)
                            :service/key :simulant.sim/processState}])
        (tx-ent id))))

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

(defmulti await-processes-ready
  "Returns a future that will be realized when all of a sim's
   processes are ready to begin"
  (fn [conn sim] (getx sim :sim/type)))

(defmulti process-agent-ids
  "Given a process that has joined a sim, return the set of that
   process's agent ids"
  (fn [process] (getx process :process/type)))

(defmulti start-clock
  "Start the sim clock, returns the updated clock"
  (fn [conn clock] (getx clock :clock/type)))

(defmulti sleep-until
  "Sleep until sim clock reaches clock-elapsed-time"
  (fn [clock elapsed] (getx clock :clock/type)))

(defmulti clock-elapsed-time
  "Return the elapsed simulation time, in msec, or nil
   if clock has not started"
  (fn [clock] (getx clock :clock/type)))

;; ## Clock
(defn sim-time-up?
  "Has the time allotment for this sim expired?"
  [sim]
  (if-let [clock (get sim :sim/clock)]
    (if-let [elapsed (clock-elapsed-time clock)]
      (< (-> sim :test/_sims only :test/duration) elapsed)
      false)
    false))

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

(defn await-processes-ready
  [sim-conn sim]
  (logged-future
   (while
    (<
     (ffirst (d/q '[:find (count ?procid)
                    :in $ ?simid
                    :where [?simid :sim/processes ?procid]]
                  (d/db sim-conn) (e sim)))
     (:sim/processCount sim))
    (Thread/sleep 1000))))

;; The default implementation of process-agent-ids assumes that all
;; processes in the sim should have agents allocated round-robin
;; across them.
(defmethod process-agent-ids :default
  [process]
  (let [sim (-> process :sim/_processes only)
        test (-> sim :test/_sims only)
        nprocs (:sim/processCount sim)
        ord (:process/ordinal process)]
    (->> (d/q '[:find ?e
            :in $ ?test
            :where
            [?test :test/agents ?e]]
          (d/entity-db test) (e test))
         (map first)
         sort
         (keep-partition ord nprocs)
         (into #{}))))

(defn action-seq
  "Create a lazy sequence of actions for agents, which should be
    the subset of agents that this process represents. The use of
    the datoms API instead of query is important, as the number
    of actions could be huge."
  [db agent-ids]
  (->> (d/datoms db :avet :action/atTime)
       (map (fn [datom] (d/entity db (:e datom))))
       (filter (fn [action] (contains? agent-ids (-> action :agent/_actions only :db/id))))))

;; ## Fixed Clock

(defmethod start-clock :clock.type/fixed
  [conn clock]
  (let [t (System/currentTimeMillis)
        {:keys [db-after]} @(d/transact conn [[:deliver (e clock) :clock/realStart t]])]
    (d/entity db-after (e clock))))

(defmethod clock-elapsed-time :clock.type/fixed
  [clock]
  (when-let [start (get clock :clock/realStart)]
    (let [mult (getx clock :clock/multiplier)
          real-elapsed (- (System/currentTimeMillis) start)]
      (long (* real-elapsed mult)))))

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

(def simulant-send
  "Use send-via where available, fall back to send."
  (if-let [via (resolve 'clojure.core/send-via)]
    (fn [& args] (apply @via @process-executor args))
    send))

(defn feed-action
  "Feed a single action to the actor's agent."
  [action process]
  (let [agent-id (-> (getx action :agent/_actions) only :db/id)]
    (simulant-send
     (via-agent-for agent-id)
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
   (let [sim (-> process :sim/_processes only)]
     @(await-processes-ready sim-conn sim)
     (start-clock sim-conn (getx sim :sim/clock)))
   (let [db (d/db sim-conn)
         process (d/entity db (e process)) ;; reload with clock!
         agent-ids (process-agent-ids process)]
     (try
      (with-services sim-conn process
        #(do
           (feed-all-actions process (action-seq db agent-ids))
           (await-all agent-ids)
           (d/transact sim-conn [[:db/add (:db/id process) :process/state :process.state/completed]])))
      (catch Throwable t
        (.printStackTrace t)
        (d/transact sim-conn [{:db/id (:db/id process)
                               :process/state :process.state/failed
                               :process/errorDescription (stack-trace-string t)}])
        (throw t))))))

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
