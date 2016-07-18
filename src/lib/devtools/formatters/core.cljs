(ns devtools.formatters.core
  (:require-macros [devtools.util :refer [oget oset ocall oapply safe-call]])
  (:require [devtools.prefs :refer [pref]]
            [devtools.format :refer [IDevtoolsFormat]]
            [devtools.protocols :refer [IFormat]]
            [devtools.formatters.templating :refer [surrogate? render-markup]]
            [devtools.formatters.helpers :refer [cljs-value?]]
            [devtools.formatters.state :refer [prevent-recursion? *current-state* get-current-state]]
            [devtools.formatters.markup :refer [<cljs-land> <preview> <details> <standard-body-reference>]]))

; ---------------------------------------------------------------------------------------------------------------------------

(defn build-header-markup [value]
  (<cljs-land> (<preview> value)))

(defn build-surrogate-body-markup [surrogate]
  (if-let [body-template (oget surrogate "bodyTemplate")]
    body-template
    (let [target (oget surrogate "target")]
      (if (seqable? target)
        (let [starting-index (or (oget surrogate "startIndex") 0)]
          (<details> target starting-index))
        (<standard-body-reference> target)))))

; -- RAW API ----------------------------------------------------------------------------------------------------------------

(defn want-value?* [value]
  (and (not (prevent-recursion?))
       (or (cljs-value? value) (surrogate? value))))

(defn header* [value]
  (cond
    (surrogate? value) (aget value "header")
    (safe-call satisfies? false IDevtoolsFormat value) (devtools.format/-header value)
    (safe-call satisfies? false IFormat value) (devtools.protocols/-header value)
    :else (render-markup (build-header-markup value))))

(defn has-body* [value]
  ; note: body is emulated using surrogate references
  (cond
    (surrogate? value) (aget value "hasBody")
    (safe-call satisfies? false IDevtoolsFormat value) (devtools.format/-has-body value)
    (safe-call satisfies? false IFormat value) (devtools.protocols/-has-body value)
    :else false))

(defn body* [value]
  (cond
    (surrogate? value) (render-markup (build-surrogate-body-markup value))
    (safe-call satisfies? false IDevtoolsFormat value) (devtools.format/-body value)
    (safe-call satisfies? false IFormat value) (devtools.protocols/-body value)))

; ---------------------------------------------------------------------------------------------------------------------------
; RAW API config-aware, see state management documentation state.cljs

(defn config-wrapper [raw-fn]
  (fn [value config]
    (binding [*current-state* (or config {})]
      (raw-fn value))))

(def want-value? (config-wrapper want-value?*))
(def header (config-wrapper header*))
(def has-body (config-wrapper has-body*))
(def body (config-wrapper body*))

; -- API CALLS --------------------------------------------------------------------------------------------------------------

(defn wrap-with-exception-guard [f]
  (fn [& args]
    (try
      (apply f args)
      (catch :default e
        (.error js/console "CLJS DevTools internal error:" e)
        nil))))

(defn build-api-call [raw-fn pre-handler-key post-handler-key]
  "Wraps raw API call in a function which calls pre-handler and post-handler.

   pre-handler gets a chance to pre-process value before it is passed to cljs-devtools
   post-handler gets a chance to post-process value returned by cljs-devtools."
  (let [handler (fn [value config]
                  (let [pre-handler (or (pref pre-handler-key) identity)
                        post-handler (or (pref post-handler-key) identity)
                        preprocessed-value (pre-handler value)
                        result (if (want-value? preprocessed-value config)
                                 (raw-fn preprocessed-value config))]
                    (post-handler result)))]
    (wrap-with-exception-guard handler)))

(def header-api-call (build-api-call header :header-pre-handler :header-post-handler))
(def has-body-api-call (build-api-call has-body :has-body-pre-handler :has-body-post-handler))
(def body-api-call (build-api-call body :body-pre-handler :body-post-handler))