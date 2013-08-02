;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns simulant.util
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
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
  (when-let [result (-> (apply d/q query db args) ssolo)]
    (d/entity db result)))

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

(defn transact-pbatch
  "Submit txes in batches of size batch-size, default is 100"
  ([conn txes] (transact-pbatch conn txes 100))
  ([conn txes batch-size]
     (->> (partition-all batch-size txes)
          (pmap #(d/transact-async conn (mapcat identity %)))
          (map deref)
          dorun)
     :ok))

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
  (edn/read-string str))

(defn form-seq
  "Lazy seq of forms read from a reader"
  [reader]
  (let [form (read reader false reader)]
    (when-not (= form reader)
      (cons form (lazy-seq (form-seq reader))))))

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

;; Git helpers & friends

(defn- ^java.io.Reader exec-stream
  [^String cmd]
  (-> (Runtime/getRuntime)
      (.exec cmd)
      .getInputStream
      io/reader))

(defn git-repo-uri
  "Returns git origin repo uri"
  []
  (with-open [s (exec-stream (str "git remote show -n origin"))]
    (let [es (line-seq s)
          ^String line (second es)
          uri (subs line (inc (.lastIndexOf line " ")))]
      uri)))

(defn git-latest-sha
  "Returns the most recent SHA of HEAD (excluding un-committed changes.)"
  []
  (with-open [s (exec-stream (str "git rev-parse HEAD"))] (first (line-seq s))))

(defn gen-codebase
  "Generate a codebase entity representing the current codebase"
  []
  {:db/id (d/tempid :db.part/user)
   :repo/type :repo.type/git
   :git/uri (git-repo-uri)
   :git/sha (git-latest-sha)})
