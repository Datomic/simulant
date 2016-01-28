(ns simulant.http
  "Bare-bones HTTP client implementation."
  (:require [clojure.data.json :as json])
  (:import [javax.net.ssl HostnameVerifier]
           [org.apache.http HttpEntity HttpMessage HttpRequestInterceptor HttpResponse]
           [org.apache.http.client.entity UrlEncodedFormEntity]
           [org.apache.http.client.methods HttpGet HttpHead HttpPost HttpPut]
           [org.apache.http.client.protocol HttpClientContext]
           [org.apache.http.conn.ssl SSLConnectionSocketFactory]
           [org.apache.http.entity StringEntity]
           [org.apache.http.impl.client BasicCookieStore CloseableHttpClient HttpClients]
           [org.apache.http.impl.cookie BasicClientCookie]
           [org.apache.http.message BasicNameValuePair]
           [org.apache.http.ssl SSLContextBuilder TrustStrategy]
           [org.apache.http.util EntityUtils]))

(defn cookies
  "Returns the cookies stored in the specified cookie-store"
  [cs]
  (->> cs
    .getCookies
    (map (fn [cookie] [(.getName cookie)
                       {:value       (.getValue cookie)
                        :domain      (.getDomain cookie)
                        :comment     (.getComment cookie)
                        :comment-url (.getCommentURL cookie)
                        :expiry      (.getExpiryDate cookie)
                        :path        (.getPath cookie)
                        :port        (into [] (.getPorts cookie))
                        :version     (.getVersion cookie)
                        :persistent? (.isPersistent cookie)
                        :secure?     (.isSecure cookie)}]))
    (into (sorted-map))))

(defn cookie-store
  []
  (BasicCookieStore.))

(defn add-cookie!
  [cs {:keys [name value domain]}]
  (.addCookie cs (doto (BasicClientCookie. name value)
                   (.setVersion 1)
                   (.setDomain domain))))

(def method-ctors
  {:get #(HttpGet. %)
   :post #(HttpPost. %)
   :put #(HttpPut. %)
   :head #(HttpHead. %)})

(defn message->headers
  "Returns a map of the headers in a request or response object.
  Map values will be a vector of the header values."
  [^HttpMessage message]
  (->> message
       .getAllHeaders
       (map (juxt #(.getName %) #(.getValue %)))
       (reduce (fn [m [k v]]
                 (update-in m [k] #(if % (conj % v) [v])))
               (sorted-map))))

(defn ^CloseableHttpClient build-client
  [request {:keys [insecure? cookie-store] :as opts}]
  (.build (cond-> (HttpClients/custom)
            cookie-store
            (.setDefaultCookieStore cookie-store)

            true
            (.addInterceptorLast
             (reify HttpRequestInterceptor
               (process [this request context]
                 (.setAttribute context
                                "request-headers"
                                (message->headers request)))))
            insecure?
            (.setSSLSocketFactory (SSLConnectionSocketFactory.
                                   (.build (.loadTrustMaterial
                                            (SSLContextBuilder.)
                                            nil
                                            (reify TrustStrategy
                                              (isTrusted [_ _ _]
                                                true))))
                                   (reify HostnameVerifier
                                     (verify [this hostname session]
                                       true)))))))

(def keyword->content-type
  {:json "application/json"})

(defn set-content-type
  "Set the content-type header appropriately on method."
  [method content-type]
  (when content-type
    (.setHeader method "Content-Type" (keyword->content-type content-type))))

(defn set-form-params
  "Encode the form parameters into the body of method, using the
  appropriate encoding given the content-type."
  [method content-type form-params]
  (when form-params
    (.setEntity method
                (if (= :json content-type)
                  (-> form-params json/write-str StringEntity.)
                  (->> form-params
                    (map (fn [[k v]]
                           (BasicNameValuePair. (name k) (str v))))
                    (into [])
                    UrlEncodedFormEntity.)))))

(defn set-body
  "Set the body of the method to the specified `body` string."
  [method ^String body]
  (when body
    (.setEntity method (StringEntity. body))))

(defn request-body
  "Return the request body of method as a string, if it can be
  returned."
  [method]
  (when (instance? org.apache.http.client.methods.HttpEntityEnclosingRequestBase method)
    (some-> method .getEntity EntityUtils/toString)))

(defn generate-client
  "Given a cookie-store, return an HTTP client. An HTTP client is a
  function that takes a map with keys...

  * :method - A keyword, :get or :post.
  * :url - The full URL to request.
  * :headers - The headers to include.
  * :content-type - A string indicating the content type.
  * :form-params - Optional. A map from keywords or strings to strings
      of request params. Will be encoding according to :content-type.
  * :body - Optional. A string containing the body of the request.
      Overrides :form-params if present.
  * :insecure? - Optional. If true, ignores invalid server certificates.

  and returns a map with keys...

  * :body - The body of the response as a string.
  * :headers - The headers that were returned.
  * :request - The request map, with :headers altered to match what
      was actually sent, and the :body and :url that were used.
  * :status - The http response status as a number"
  [cs]
  (fn [{:keys [url body headers content-type form-params] :as request}]
    (let [method  (-> request :method method-ctors (.invoke url))
          context (HttpClientContext/create)]
      (set-content-type method content-type)
      (if body
        (set-body method body)
        (set-form-params method content-type form-params))
      (doseq [[k v] headers]
        (.addHeader method (name k) (str v)))
      (with-open [client (build-client request {:cookie-store cs
                                                :insecure? (:insecure? request)})]
        (let [response (.execute client method context)
              status   (-> response .getStatusLine .getStatusCode)
              body     (when-let [entity (.getEntity response)]
                         (EntityUtils/toString entity))
              request* (assoc request
                              :headers (-> context (.getAttribute "request-headers"))
                              :url url
                              :body (request-body method))
              result   {:body    body
                        :headers (message->headers response)
                        :status  status
                        :request request*}]
          result)))))
