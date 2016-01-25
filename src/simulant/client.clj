(ns simulant.client
  "Client and middleware for web services."
  (:require [simulant.http :as http]
            [simulant.action-log :refer [action-log action-log-entry]]))


(letfn [(http-header [n v] {:http.header/name n :http.header/value v})]
  (defn- headers-log
    [headers]
    (mapcat (fn [[name value]]
              (map #(http-header name %) value))
            headers)))

(defn send-action-log-entry
  [action process {:keys [status body request] :as response} duration-ms]
  (let [entry (merge (action-log-entry action process)
                     {:actionLog/http-request {:http/method (:method request)
                                               :http/url (:url request)
                                               :http/headers (headers-log (:headers request))
                                               :http/body (or (:body request) "")}
                      :actionLog/http-response {:http/status status
                                                :http/body body
                                                :http/headers (headers-log (:headers response))}}
                     (when duration-ms
                       {:actionLog/nsec (* duration-ms 1000 1000)}))]
    (action-log [entry])
    (:actionLog/id entry)))

(defn wrap-action-log
  "Record the HTTP request and response to the action log.

   Accepts a `:skip-logging?` key on the request map to opt out of
   action-logging the request."
  [http action process]
  (let [send (partial send-action-log-entry action process)]
    (fn [request]
      (try
        (let [start (System/currentTimeMillis)
              response (http request)
              stop (System/currentTimeMillis)]
          (cond-> response
            (not (:skip-logging? request))
            (assoc :action-log-id (send response (- stop start)))))
        (catch clojure.lang.ExceptionInfo e
          ;; Stop the normal execution path but still action-log the
          ;; HTTP request and response.
          (let [message (.getMessage e)
                data (ex-data e)
                data (cond-> data
                       (not (:skip-logging? request))
                       (assoc-in [:response :action-log-id]
                                 (send (:response data) nil)))]
            (throw (ex-info message data))))))))

(defn wrap-retries
  "Adds retry if a :retry? predicate (called with the result of the
  HTTP call, either a response map or an exception, and the number of
  attempts) is present in the request."
  [http]
  (fn [{:keys [retry?] :as request}]
    (if-not retry?
      (http request)
      (loop [attempts 1]
        (let [response-or-exception (try (http request) (catch Throwable t t))]
          (if (retry? response-or-exception attempts)
            (recur (inc attempts))
            (if (instance? Throwable response-or-exception)
              (throw response-or-exception)
              response-or-exception)))))))

(def success-codes (set (range 200 300)))

(defn successful-2xx?
  "Is the HTTP response's status code in the 200s?"
  [response]
  (contains? success-codes (:status response)))

(defn wrap-exceptions
  "Throw an ex-info if the HTTP request was unsuccessful.

  Accepts a `:success-pred` key on the request map to override the
  predicate that determines success."
  [http]
  (fn [request]
    (let [success-pred (or (:success-pred request) successful-2xx?)
          response (http request)]
      (if (success-pred response)
        response
        (throw (ex-info "HTTP response status was not success"
                        {:reason   ::unsuccessful-http-response
                         :response response}))))))

(defn create
  "Return a function to issue HTTP requests against web services."
  ([action process]
   (create action process (http/cookie-store)))
  ([action process cookie-store]
   (-> (http/generate-client cookie-store)
       wrap-exceptions
       (wrap-action-log action process)
       wrap-retries)))
