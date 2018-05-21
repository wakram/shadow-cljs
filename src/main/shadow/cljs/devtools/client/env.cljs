(ns shadow.cljs.devtools.client.env
  (:require
    [goog.object :as gobj]
    [clojure.string :as str]
    [cljs.tools.reader :as reader]
    [cljs.pprint :refer (pprint)]
    ))

;; FIXME: make this persistent somehow?
(defonce runtime-id (random-uuid))

(goog-define enabled false)

(goog-define autoload true)

(goog-define module-format "goog")

(goog-define reload-with-state false)

(goog-define build-id "")

(goog-define proc-id "")

(goog-define repl-host "")

(goog-define repl-port 8200)

(goog-define repl-pprint false)

(goog-define use-document-host true)

(goog-define devtools-url "")

(goog-define ssl false)

(goog-define ignore-warnings false)

(defn get-repl-host []
  (if (and use-document-host
           js/goog.global.document
           js/goog.global.document.location
           (seq js/goog.global.document.location.hostname))
    js/document.location.hostname
    repl-host))

(defn get-url-base []
  (if (seq devtools-url)
    devtools-url
    (str "http" (when ssl "s") "://" (get-repl-host) ":" repl-port)))

(defn get-ws-url-base []
  (-> (get-url-base)
      (str/replace #"^http" "ws")))

(defn ws-url [runtime-type]
  {:pre [(keyword? runtime-type)]}
  (str (get-ws-url-base) "/ws/worker/" build-id "/" proc-id "/" runtime-id "/" (name runtime-type)))

(defn ws-listener-url [client-type]
  (str (get-ws-url-base) "/ws/listener/" build-id "/" proc-id "/" runtime-id))

(defn files-url []
  (str (get-url-base) "/worker/files/" build-id "/" proc-id "/" runtime-id))

(def repl-print-fn
  (if-not repl-pprint
    pr-str
    (fn repl-pprint [obj]
      (with-out-str
        (pprint obj)
        ))))

(defn repl-error [e]
  (-> {:type :repl/invoke-error
       ;; FIXME: may contain non-printable things and would break the client read
       ;; :ex-data (ex-data e)
       :error (.-message e)}
      (cond->
        (.hasOwnProperty e "stack")
        (assoc :stack (.-stack e)))))

(defn repl-call [repl-expr repl-error]
  (try
    (let [result {:type :repl/result}
          ret (repl-expr)]
      (set! *3 *2)
      (set! *2 *1)
      (set! *1 ret)

      (try

        (assoc result
          :value (repl-print-fn ret))
        (catch :default e
          (js/console.log "encoding of result failed" e ret)
          (assoc result :error "ENCODING FAILED"))))
    (catch :default e
      (set! *e e)
      (repl-error e)
      )))

;; FIXME: this need to become idempotent somehow
;; but is something sets a print-fn we can't tell if that
;; will actually call ours. only a problem if the websocket is
;; reconnected though
(defonce reset-print-fn-ref (atom nil))

(defn set-print-fns! [msg-fn]
  ;; cannot capture these before as they may change in between loading this file
  ;; and running the websocket connect. the user code is loaded after this file
  (let [original-print-fn cljs.core/*print-fn*
        original-print-err-fn cljs.core/*print-err-fn*]

    (reset! reset-print-fn-ref
      (fn reset-print-fns! []
        (set-print-fn! original-print-fn)
        (set-print-err-fn! original-print-err-fn)))

    (set-print-fn!
      (fn repl-print-fn [& args]
        (msg-fn {:type :repl/out :text (str/join "" args)})
        (when original-print-fn
          (apply original-print-fn args))))

    (set-print-err-fn!
      (fn repl-print-err-fn [& args]
        (msg-fn {:type :repl/err :text (str/join "" args)})
        (when original-print-err-fn
          (apply original-print-err-fn args))))))

(defn reset-print-fns! []
  (when-let [x @reset-print-fn-ref]
    (x)
    (reset! reset-print-fn-ref nil)))

(defn process-ws-msg [text handler]
  (binding [reader/*default-data-reader-fn*
            (fn [tag value]
              [:tagged-literal tag value])]
    (try
      (let [msg (reader/read-string text)]
        (handler msg))
      (catch :default e
        (js/console.warn "failed to parse websocket message" text e)
        (throw e)))))

(defn make-task-fn [{:keys [log-missing-fn log-call-async log-call]} {:keys [fn-sym fn-str async]}]
  (fn [next]
    (try
      (let [fn-obj (js/goog.getObjectByName fn-str js/$CLJS)]
        (cond
          (nil? fn-obj)
          (do (when log-missing-fn
                (log-missing-fn fn-sym))
              (next))

          async
          (do (when log-call-async
                (log-call-async fn-sym))
              (fn-obj next))

          :else
          (do (when log-call
                (log-call fn-sym))
              (fn-obj)
              (next))))
      (catch :default ex
        (js/console.warn "error when calling lifecycle function" (str fn-sym) ex)
        (next)))))

(defn do-js-reload* [[task & remaining-tasks]]
  (when task
    (task #(do-js-reload* remaining-tasks))))

(defn do-js-reload
  "should pass the :build-complete message and an additional callback
   which performs the actual loading of the code (sync)
   will call all before/after callbacks in order"
  ([msg load-code-fn]
   (do-js-reload msg load-code-fn (fn [])))
  ([{:keys [reload-info] :as msg} load-code-fn complete-fn]
   (let [load-tasks
         (-> []
             ;; unload is FILO
             (into (->> (:before-load reload-info)
                        (map #(make-task-fn msg %))
                        (reverse)))
             (conj (fn [next]
                     (load-code-fn)
                     (next)))
             ;; load is FIFO
             (into (map #(make-task-fn msg %)) (:after-load reload-info))
             (conj (fn [next]
                     (complete-fn)
                     (next))))]

     (do-js-reload* load-tasks))))

(defn before-load-src [{:keys [type ns] :as src}]
  (when (= :cljs type)
    (doseq [x js/goog.global.SHADOW_NS_RESET]
      (x ns))))
