;;   Copyright (c) Metadata Partners, LLC. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns simulant.action-log
  "Library of helper functions for working with the Action Log
  service (defined in sim.clj). Use of this library is optional - it
  is intended to provide a useful layer on top of the base action log
  facilities in `simulant.sim`.

  In addition to the basic functionality provided by the action log,
  this library defines the concept of `steps`. A step is a grouping of
  entries in the action log, associated with execution of a particular
  action. For instance, if an `add item to shopping cart` action
  requires interaction with both the `item` and `cart` web services,
  the action log might contain entries for communication with both of
  those services, each of which could be tagged as a separate step
  involved in executing the overall action.

  In the case of things like multiple retries against a service, order
  of action log entires may matter. Therefore, action log entries
  logged via this library also include a sequence ID. Sequence ids
  imply a partial temporal order: all entries logged from a single
  thread will be tagged with monotonically increasing sequence IDs,
  but no guarantees are provided for action log entries recorded from
  two different threads. In the current implementation of simulant,
  actions for a single Simulant agent are executed sequentially,
  which, in the absence of use of threads within an action
  implementation, results in action log entries being tagged in
  chronological order."
  (:require [datomic.api :as d]
            [simulant.sim :as sim]
            [simulant.util :refer :all]))

(def ^{:doc "A counter to sort action log entries."}
  seq-counter (atom 0))

(def ^{:doc "The current step to group action log entries under."
       :dynamic true}
  *current-step* nil)

(defn create-step
  "Return tx-data to create a new step of the given type."
  [type]
  {:db/id (d/tempid :db.part/user)
   :step/type type})

(defmacro with-step
  "Use this macro to wrap code that create action log entries to
  group them under the same step."
  [type & body]
  `(binding [*current-step* (create-step ~type)]
     ~@body))

(defn action-log-entry
  "Return transaction data for an action log entry, to include a
  sequence number, and a references to the action and process. If a
  current step is defined, also include a reference to it."
  [action process]
  (merge {:db/id (d/tempid :db.part/user)
          :actionLog/id (d/squuid)
          :actionLog/sequence-number (swap! seq-counter inc)
          :actionLog/sim (-> process :sim/_processes only e)
          :actionLog/action (e action)}
         (when *current-step*
           {:actionLog/step *current-step*})))

(defn action-log
  "Log an action log entry. `entry` is transaction data representing an
  action log entry."
  [entry]
  (let [action-log (getx sim/*services* :simulant.sim/actionLog)]
    (action-log entry)))
