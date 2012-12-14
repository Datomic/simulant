(ns datomic.sim.util
  (:require [clojure.set :as set]
            [datomic.api :as d]))

(defn require-keys
  "Throws an exception unless the map m contains all keys named in ks"
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

(defn getx-in
  "Like two-argument get-in, but throws an exception if the key is
   not found."
  [m ks] 
  (reduce getx m ks))

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

(defn ssolo
  "Same as (solo (solo coll))"
  [coll]
  (solo (solo coll)))

(defn oonly
  "Same as (only (only coll))"
  [coll]
  (only (only coll)))

(defn qe
  "Returns the single entity returned by a query."
  [query db & args]
  (when->> (apply d/q query db args) ssolo (d/entity db)))

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
  (->> (apply d/q query db args)
       (mapv (fn [items]
               (mapv (partial d/entity db) items)))))

(defn find-all-by
  "Returns all entities possessing attr."
  [db attr]
  (qes '[:find ?e
         :in $ ?attr
         :where [?e ?attr]]
       db attr))

(defn transact-batch
  "Submit txes in batches of size batch-size, default is 100"
  ([conn txes] (transact-batch conn txes 100))
  ([conn txes batch-size]
     (doseq [batch (partition-all batch-size txes)]
       @(d/transact-async conn (mapcat identity batch))
       :ok)))

(defn tx-ent
  "Resolve entity id to entity as of the :db-after value of a tx result"
  [txresult eid]
  (let [{:keys [db-after tempids]} txresult]
    (d/entity db-after (d/resolve-tempid db-after tempids eid))))

(defn tx-entids
  "Resolve entity ids to entities as of the :db-after value of a tx result"
  [txresult eids]
  (let [{:keys [db-after tempids]} txresult]
    (->> eids
         (map #(d/resolve-tempid db-after tempids %))
         sort)))

(defn count-by
  "Count the number of entities possessing attribute attr"
  [db attr]
  (->> (d/q '[:find (count ?e)
              :in $ ?attr
              :where [?e ?attr]]
            db attr)
       ffirst))

(defprotocol Eid
  "A simple protocol for retrieving an object's id."
  (e [_] "identifying id for a value"))

(extend-protocol Eid
  java.lang.Long
  (e [n] n)

  datomic.Entity
  (e [ent] (:db/id ent))

  java.util.Map
  (e [ent] (:db/id ent)))

(defn hours->msec
  "Convert hours to milliseconds (as a long)"
  ^long [h] (long (* h 60 60 1000)))

(defn safe-read-string
  "Read a string without evaluating its contents"
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

(defn stack-trace-string
  "String containing the stack trace of a Throwable t"
  [^Throwable t]
  (let [s (java.io.StringWriter.)
        ps (java.io.PrintWriter. s)]
    (.printStackTrace t ps)
    (str s)))

