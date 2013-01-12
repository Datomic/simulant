;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns simulant.examples.repl
  (:require [datomic.api :as d]
            [clojure.java.io :as io]))

(defn reset-conn
  "Reset connection to a scratch database. Use memory database if no
   URL passed in."
  ([]
     (reset-conn (str "datomic:mem://" (d/squuid))))
  ([uri]
     (d/delete-database uri)
     (d/create-database uri)
     (d/connect uri)))

(defn load-schema
  [conn resource]
  (let [m (-> resource io/resource slurp read-string)]
    (doseq [v (vals m)]
      (doseq [tx v]
        (d/transact conn tx)))))

(defn convenient
  []
  (set! *warn-on-reflection* true)
  (set! *print-length* 20)
  (use 'datomic.math)
  (use 'simulant.util)
  (require
   '[clojure.string :as str]
   '[clojure.java.io :as io]
   '[clojure.set :as set]
   '[clojure.data.generators :as gen]
   '[clojure.pprint :as pprint]
   '[simulant.sim :as sim]
   '[datomic.api :as d]))
