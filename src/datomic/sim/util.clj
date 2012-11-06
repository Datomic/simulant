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

(def ssolo (comp solo solo))

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

