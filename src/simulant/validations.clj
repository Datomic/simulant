;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns simulant.validations
  (:require [datomic.api :as d]))

(defn missing-multimethod-dispatch
  "Assuming values of attribute attr are keywords driving a multimethod mfn,
   return a collection of attr values that cannot be dispatched by mfn."
  [db attr mfn]
  (let [vs (->> (d/q '[:find ?kw
                       :in $ ?attr
                       :where
                       [_ ?attr ?v]
                       [?v :db/ident ?kw]]
                     db attr)
                (map first)
                (into #{}))]
    (remove #(get-method mfn %) vs)))

