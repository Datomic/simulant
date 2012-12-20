;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns simulant.examples.trading
  (:use simulant.util)
  (:require [datomic.api :as d]))

(defn trade
  [conn from to amount]
  (let [tx (d/tempid :db.part/tx)]
    (d/transact
     conn
     [[:db/add tx :transfer/amount amount]
      [:db/add tx :transfer/from (e from)]
      [:db/add tx :transfer/to (e to)]]))  )

(defn balance
  [db trader-id]
  (let [adds (d/q '[:find ?adds ?tx
                    :in $ ?trader
                    :where
                    [?tx :transfer/to ?trader]
                    [?tx :transfer/amount ?adds]]
                  db trader-id)
        subtracts (d/q '[:find ?subtracts ?tx
                         :in $ ?trader
                         :where
                         [?tx :transfer/from ?trader]
                         [?tx :transfer/amount ?subtracts]]
                       db trader-id)]
    (+ (getx (d/entity db trader-id) :trader/initialBalance)
       (apply + (map first adds))
       (- (apply + (map first subtracts))))))
