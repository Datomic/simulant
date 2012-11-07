(ns datomic.sim.util
  (:use datomic.api)
  (:require [clojure.set :as set]))

(defn require-keys
  [m & ks]
  (let [missing (set/difference (apply hash-set ks)
                                (apply hash-set (keys m)))]
    (when (seq missing)
      (throw (ex-info "Missing required keys" {:map m :missing missing})))))

(defn getx
  "Like two-argument get, but throws an exception if the key is
   not found."
  [m k] 
  (let [e (get m k ::sentinel)]
    (if-not (= e ::sentinel)
      e 
      (throw (ex-info "Missing required key" {:map m :key k})))))

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

(defn only
  "Like first, but throws unless exactly one item."
  [coll]
  (assert (not (next coll)))
  (if-let [result (first coll)]
    result
    (assert false)))

(def ssolo (comp solo solo))

(defn qe
  "Returns the single entity returned by a query."
  [query db & args]
  (when->> (apply q query db args) ssolo (entity db)))

(defn find-by
  "Returns the unique entity identified by attr and val."
  [db attr val]
  (qe '[:find ?e
        :in $ ?attr ?val
        :where [?e ?attr ?val]]
      db attr val))

(defn qes
  "Returns the entities returned by a query, assuming that
   all :find results are entity ids."
  [query db & args]
  (->> (apply q query db args)
       (mapv (fn [items]
               (mapv (partial entity db) items)))))

(defn find-all-by
  "Returns all entities possessing attr."
  [db attr]
  (qes '[:find ?e
         :in $ ?attr
         :where [?e ?attr]]
       db attr))

(defn transact-batch
  "Submit txes in batches of size batch-size."
  [conn txes batch-size]
  (doseq [batch (partition-all batch-size txes)]
    @(transact-async conn (mapcat identity batch))
    :ok))

(defn tx-ent
  [txresult eid]
  (let [{:keys [db-after tempids]} txresult]
    (entity db-after (resolve-tempid db-after tempids eid))))

(defn tx-entids
  [txresult eids]
  (let [{:keys [db-after tempids]} txresult]
    (->> eids
         (map #(resolve-tempid db-after tempids %))
         sort)))

(defn count-by
  [db attr]
  (->> (q '[:find (count ?e)
              :in $ ?attr
              :where [?e ?attr]]
            db attr)
       ffirst))

(defprotocol Eid
  (e [_]))

(extend-protocol Eid
  java.lang.Long
  (e [n] n)

  datomic.Entity
  (e [ent] (:db/id ent)))

(defn hours->msec [h] (* h 60 60 1000))

(defn safe-read-string 
  [str]
  (binding [*read-eval* false]
    (when (pos? (count str))
      (read-string str))))

(defmacro logged-future
  "Future with logging of failure."
  [& body]
  `(future
    (try
     ~@body
     (catch Throwable t#
       (.printStackTrace t#)
       (throw t#)))))
