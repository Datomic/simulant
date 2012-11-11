(ns datomic.sim
  (:use datomic.api datomic.sim.util)
  (:require [clojure.test.generative.event :as event]
            [clojure.java.io :as io])
  (:import [java.util.concurrent Executors]))

(set! *warn-on-reflection* true)

;; generate model & create-model have no multimethods

(defmulti create-test
  "Execute a series of transactions agaist conn that create a
   test based on model. Default implementation calls generate-test
   to generate the transactions, and then batches and submits
   them. Returns test entity."
  (fn [conn model test] (getx model :model/type)))

(defmulti generate-test
  "Generate a series of transactions that constitute a test
   based on model"
  (fn [model test] (getx model :model/type)))

(defmulti create-sim
  "Execute a series of transactions agaist conn that create a
   sim based on test. Default implementation calls generate-sim
   to generate the transactions, and then submits them"
  (fn [conn test sim] (getx test :test/type)))

(defmulti generate-sim
  "Generate a series of transactions that constitute a sim
   based on test, plus a map of sim configuration data."
  (fn [test sim] (getx test :test/type)))

(defmulti join-sim
  "Returns a process entity or nil if could not join."
  (fn [conn sim process] (getx sim :sim/type)))

(defmulti process-agents
  "Given a process that has joined a sim, return that process's
   agents"
  (fn [process] (getx process :process/type)))

(defmulti perform-action
  "Perform an action"
  (fn [action sim] (getx action :action/type)))

(defmulti start-clock
  "Start the sim clock, returns the updated clock"
  (fn [conn clock] (getx clock :clock/type)))

(defmulti sleep-until
  "Sleep until sim clock reaches clock-elapsed-time"
  (fn [clock elapsed] (getx clock :clock/type)))

(defmulti clock-elapsed-time
  "Return the elapsed simulation time, in msec"
  (fn [clock] (getx clock :clock/type)))

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
(def default-executor
  (delay
   (Executors/newFixedThreadPool 100)))

(def process-executor
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
    (-> @(transact conn [[:sim/join (e sim) (assoc process
                                              :process/type ptype
                                              :process/state :process.state/running
                                              :process/uuid (squuid))]])
        (tx-ent id))))

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
  [db agents]
  (let [agent-ids (->> agents
                       (map :db/id)
                       (into #{}))]
    (->> (datoms db :avet :action/atTime)
         (map (fn [datom] (entity db (:e datom))))
         (filter (fn [action] (contains? agent-ids (-> action :agent/_actions only :db/id)))))))

;; sim time (fixed clock) ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod start-clock :clock.type/fixed
  [conn clock]
  (let [t (System/currentTimeMillis)
        {:keys [db-after]} @(transact conn [[:deliver (e clock) :clock/realStart t]])]
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
      (Thread/sleep (long (/ (- twhen tnow) (getx clock :clock/multiplier)))))))

(defn generate-fixed-clock
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
  (let [id (get clock :db/id (tempid :sim))]
    (-> @(transact conn (generate-fixed-clock sim (assoc clock :db/id id)))
        (tx-ent id))))

;; action logging ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn process-log-file
  [process]
  (str (:process/uuid process) "-events.clj"))

(defmacro with-process-log
  [process & body]
  `(with-open [^java.io.Writer f# (io/writer (process-log-file ~process))]
     (let [handler# (fn [m#]
                      (.write f# (pr-str m#))
                      (.write f# 10)
                      (.flush f#))]
       (event/with-handler handler#
         ~@body))))

(defn action-log->data
  [process coll]
  (->> coll
       (filter #(= :sim/action (:type %)))
       (map (fn [line]
              ;; take advantage of log/action relationship
              ;; so that start and end events get same tempid
              (let [logid (tempid :log (- (getx line :sim/action)))]
                (if (contains? (:tags line) :begin)
                  [{:db/id logid
                    :log/action (getx line :sim/action)
                    :log/process (e process)
                    :log/actionStart (getx line :tstamp)}]
                  [[:db/add logid :log/actionEnd (getx line :tstamp)]]))))))

(defn transact-action-logs
  [sim-conn process]
  (with-open [f (io/reader (process-log-file process))]
    (transact-batch sim-conn (action-log->data process (map read-string (line-seq f))) 1000)))

;; sim runner ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn handle-action-error
  [sim-agent agent ^Throwable error]
  (.printStackTrace error))

(def ^:private via-agent-for
  (memoize
   (fn [sim-agent]
     (assert sim-agent)
     (let [a (agent nil)]
       (set-error-handler! a (partial handle-action-error sim-agent))
       (set-error-mode! a :fail)
       a))))

(defn feed-action
  "Feed a single action to the actor's agent."
  [action sim]
  (let [actor (-> (getx action :agent/_actions) only)]
    (send-via
     @process-executor
     (via-agent-for actor)
     (fn [agent-state]
       (event/report :sim/action :sim/action (:db/id action) :tags #{:begin})
       (perform-action action sim)
       (event/report :sim/action :sim/action (:db/id action) :tags #{:end})
       agent-state))))

(defn feed-all-actions
  "Feed all actions, which should be sorted by ascending :action/atTime"
  [sim actions]
  (let [clock (getx sim :sim/clock)]
    (doseq [{elapsed :action/atTime :as action} actions]
      (sleep-until clock elapsed)
      (feed-action action sim))))

(defn stack-trace-string
  [^Throwable t]
  (let [s (java.io.StringWriter.)
        ps (java.io.PrintWriter. s)]
    (.printStackTrace t ps)
    (str s)))

(defn await-all
  "Given a collection of objects, calls await on the agent for each one"
  [coll]
  (apply await (map via-agent-for coll)))

(defn process-loop
  "Returns a future"
  [sim-conn process]
  (logged-future
   (with-process-log process
     (let [sim (-> process :sim/_processes only)
           agents (process-agents process)
           actions (action-seq (db sim-conn) agents)]
       (try
        (feed-all-actions sim actions)
        (await-all agents)
        (catch Throwable t
          (.printStackTrace t)
          (transact sim-conn [{:db/id (:db/id process)
                               :process/state :process.state/failed
                               :process/errorDescription (stack-trace-string t)}])
          (throw t)))))
   (transact sim-conn [[:db/add (:db/id process) :process/state :process.state/completed]])
   (transact-action-logs sim-conn process)))

(defn run-sim-process
  "Backgrounds process loop and returns process object. Returns map
   with keys :process and :runner (a future), or nil if unable to run sim"
  [sim-uri sim-id]
  (let [sim-conn (connect sim-uri)
        sim (entity (db sim-conn) sim-id)]
    (when-let [process (start-sim sim-conn sim {:db/id (tempid :sim)})]
      (let [fut (process-loop sim-conn process)]
        {:process process :runner fut}))))

(defn -main
  [sim-uri sim-id]
  (if-let [process (run-sim-process sim-uri (safe-read-string sim-id))]
    (println "Joined sim " sim-id " as process " (:db/id process))
    (println "Unable to join sim " sim-id)))

;; queries ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn action-time
  [action]
  (- (getx action :log/actionEnd)
     (getx action :log/actionStart)))

(defn action-log-entries
  "Find the action entries for a sim, scoped to given action-types"
  [db sim & action-types]
  (qes '[:find ?e
         :in $ ?sim [?action-type ...]
         :where
         [?sim :sim/processes ?process]
         [?e :log/process ?process]
         [?e :log/action ?action]
         [?action :action/type ?action-type]]
       db
       (e sim)
       action-types))


