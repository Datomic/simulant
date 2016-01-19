(ns simulant.interrupt
  "Will stop an agent as soon as an action throws an exception.

  The subsequent actions will be skipped and an action log entry created to indicate it.

  To use the feature:
  * add the service to your sim
  * call `perform-action` inside the body of your actions or
    use the `defaction` macro."
  (:require [clojure.stacktrace :as stacktrace]
            [datomic.api :as d]
            [simulant.sim :as sim]
            [simulant.util :refer :all]))

(defrecord InterruptService [state]
  sim/Service
  (start-service [this process])
  (finalize-service [this process]))

(defn construct-service
  [_ _]
  (->InterruptService (atom {})))

(defn create-interrupt-service
  "Record the fact that a sim will use the interruption service."
  [conn sim]
  (let [id (d/tempid :db.part/user)]
    (-> @(d/transact conn [{:db/id id
                            :sim/_services (e sim)
                            :service/type :service.type/interrupt
                            :service/constructor (str 'simulant.interrupt/construct-service)
                            :service/key :simulant.interrupt/service}])
        (tx-ent id))))

(defn- interrupt!
  "Interrupt the agent by modifying the process's local state."
  [{:keys [state] :as service} agent]
  (swap! state assoc agent true))

(defn interrupted?
  "Wether the agent has been interrupted."
  [{:keys [state] :as service} agent]
  (get @state agent))

(defn perform-action
  "Execute the function `f`, the body of the action.

  If `f` throws an exception, record it and interrupt the agent. All
  subsequent actions invoked through `perform-action` for this agent
  will not be performed, and an action log entry noting the action as
  skipped for this agent will be recorded."
  [action process f]
  (let [action-log (getx sim/*services* :simulant.sim/actionLog)
        interrupt  (getx sim/*services* :simulant.interrupt/service)
        agent      (-> action :agent/_actions only e)]
    (if (interrupted? interrupt agent)
      (action-log [{:db/id (d/tempid :db.part/user)
                    :actionLog/sim (-> process :sim/_processes only e)
                    :actionLog/action (e action)
                    :actionLog/skipped? true}])
      (try
        (f action process)
        (catch Throwable t
          (let [message (or (.getMessage t) "Failure while executing action.")]
            (action-log [{:db/id (d/tempid :db.part/user)
                          :actionLog/sim (-> process :sim/_processes only e)
                          :actionLog/action (e action)
                          :actionLog/failure-type (-> t class str)
                          :actionLog/failure-trace (with-out-str (stacktrace/print-cause-trace t))
                          :actionLog/failure-message message}]))
          (interrupt! interrupt agent))))))

(defmacro defaction
  "Convenience macro for `perform-action`."
  [type args & body]
  `(defmethod sim/perform-action ~type [action# process#]
     (perform-action action# process# (fn ~args ~@body))))
