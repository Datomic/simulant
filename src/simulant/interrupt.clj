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
  "Execute the function `f`, a function of the action and the process,
  unless the agent has been interrupted. Interrupt the agent when an
  unhandled exception is thrown.

  If `f` throws an exception, call `notify-error` with the action,
  process, and exception and interrupt the agent. All subsequent
  actions invoked through `perform-action` for an interrupted agent
  will not be performed, and instead `notify-interrupted` will be
  called with the action and process."
  [action process f notify-interrupted notify-error]
  (let [interrupt  (getx sim/*services* :simulant.interrupt/service)
        agent      (-> action :agent/_actions only e)]
    (if (interrupted? interrupt agent)
      (notify-interrupted action process)
      (try
        (f action process)
        (catch Throwable t
          (notify-error action process t)
          (interrupt! interrupt agent))))))

(comment
  ;; Here's a way you could put together the interruption service and
  ;; the action log under a macro that you could use to implement all
  ;; your actions.
  (defn log-skipped
    [action process]
    (let [action-log (getx sim/*services* :simulant.sim/actionLog)]
      (action-log [{:db/id (d/tempid :db.part/user)
                    :actionLog/sim (-> process :sim/_processes only e)
                    :actionLog/action (e action)
                    :actionLog/skipped? true}])))


  (defn log-error
    [action process t]
    (let [action-log (getx sim/*services* :simulant.sim/actionLog)
          message (or (.getMessage t) "Failure while executing action.")]
      (action-log [{:db/id (d/tempid :db.part/user)
                    :actionLog/sim (-> process :sim/_processes only e)
                    :actionLog/action (e action)
                    :actionLog/failure-type (-> t class str)
                    :actionLog/failure-trace (with-out-str (stacktrace/print-cause-trace t))
                    :actionLog/failure-message message}])))

  (defmacro defaction
    "Convenience macro for defining actions"
    [type args & body]
    `(defmethod sim/perform-action ~type [action# process#]
       (perform-aciton action#
                       process#
                       (fn ~args ~@body)
                       log-skipped
                       log-error)))

  ;; Example usage:
  (defaction :my.action/foo
    [action process]
    (do-foo-stuff action)
    (do-more-foo-stuff process))
  )
