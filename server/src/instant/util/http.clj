(ns instant.util.http
  (:require
   [clojure.string :as string]
   [instant.util.uuid :as uuid-util]
   [instant.util.exception :as ex]
   [instant.util.tracer :as tracer]
   [ring.util.http-response :as response]))

(defn coerce-bearer-token [bearer-token]
  (some-> bearer-token
          (string/split #"Bearer ")
          last
          string/trim
          uuid-util/coerce))

(defn req->bearer-token! [req]
  (ex/get-param! req
                 [:headers "authorization"]
                 coerce-bearer-token))


(defn req->bearer-token [req]
  (if-let [header (get-in req [:headers "authorization"])]
    (coerce-bearer-token header)
    nil))

;; ----------
;; Middleware

(defn tracer-record-attrs [handler]
  (fn [request]
    (let [{:keys [uri request-method headers body query-params]} request
          app-id (get headers "app-id")
          authorization (get headers "authorization")
          cli-version (get headers "instant-cli-version")
          core-version (get headers "instant-core-version")
          admin-version (get headers "instant-admin-version")
          origin (get headers "origin")
          attrs {:request-uri uri
                 :request-method request-method
                 :method request-method
                 :origin origin
                 :app-id app-id
                 :authorization authorization
                 :query-params query-params
                 :cli-version cli-version
                 :core-version core-version
                 :admin-version admin-version
                 :body (when (map? body) body)}]
      (tracer/add-data! {:attributes attrs})
      (handler request))))

(defn tracer-wrap-span
  "Wraps standard http requests within a span."
  [handler]
  (fn [request]
    (if (:websocket? request)
      ;; We skip websocket requests; 
      ;; Because websockets are long-lived,
      ;; a parent-span doesn't make sense. 
      (handler request)
      (tracer/with-span! {:name "http-req"}
        (let [{:keys [status] :as response}  (handler request)]
          (tracer/add-data! {:attributes {:status status}})
          response)))))

(defn- instant-ex->bad-request [instant-ex]
  (let [{:keys [::ex/type ::ex/message ::ex/hint]} (ex-data instant-ex)]
    (condp contains? type
      #{::ex/record-not-found
        ::ex/record-expired
        ::ex/record-not-unique
        ::ex/record-foreign-key-invalid
        ::ex/record-check-violation
        ::ex/sql-raise
        ::ex/timeout

        ::ex/permission-denied
        ::ex/permission-evaluation-failed

        ::ex/param-missing
        ::ex/param-malformed

        ::ex/validation-failed}
      {:type (keyword (name type))
       :message message
       :hint hint}

      ;; Oauth providers expect an `error` key
      #{::ex/oauth-error}
      {:type (keyword (name type))
       :error message}

      nil)))

(defn wrap-errors
  "Captures exceptions thrown by the handler. We: 
    1. Log the exception 
    2. Return an appropriate HTTP response 

   Some `instant-ex` exceptions are converted to bad-requests."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (let [instant-ex (ex/find-instant-exception e)
              {:keys [::ex/type ::ex/message ::ex/hint]} (ex-data instant-ex)
              bad-request (when instant-ex
                            (instant-ex->bad-request instant-ex))]
          (cond
            bad-request (cond (-> bad-request :hint :args first :auth?)
                              (do (tracer/record-exception-span! e {:name "instant-ex/unauthorized"})
                                  (response/unauthorized bad-request))

                              (= type ::ex/timeout)
                              (do (tracer/record-exception-span! e {:name "instant-ex/timeout"})
                                  (response/too-many-requests bad-request))

                              :else
                              (do (tracer/record-exception-span! e {:name "instant-ex/bad-request"})
                                  (response/bad-request bad-request)))

            instant-ex (do (tracer/add-exception! instant-ex {:escaping? false})
                           (response/internal-server-error
                            {:type (keyword (name type))
                             :message message
                             :hint (assoc hint :debug-uri (tracer/span-uri))}))
            :else (do  (tracer/add-exception! e {:escaping? false})
                       (response/internal-server-error
                        {:type :unknown
                         :message "Something went wrong. Please ping `debug-uri` in #bug-and-questions, and we'll take a look. Sorry about this!"
                         :hint {:debug-uri (tracer/span-uri)}}))))))))
