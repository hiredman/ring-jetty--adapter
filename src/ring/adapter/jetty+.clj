(ns ring.adapter.jetty+
  (:require [ring.adapter.jetty :as jetty])
  (:import (java.lang.reflect Method)
           (org.mortbay.jetty Server)
           (org.mortbay.jetty.bio SocketConnector)
           (org.mortbay.jetty.security SslSocketConnector)))

(defn ->method-name [a-name]
  (->> (.split (name a-name) "-")
       (mapcat (fn [[strt & rst]] (conj rst (Character/toUpperCase strt))))
       (apply str "set")))

(defmulti create-connector identity)

(defmethod create-connector nil [_] (SocketConnector.))

(defmethod create-connector 'ssl [_] (SslSocketConnector.))

(defmethod create-connector :default [type]
  (throw
   (IllegalArgumentException. (str "Unknown connector type: " (pr-str type)))))

(def bool-type (.getComponentType (class (boolean-array 0))))

(def int-type (.getComponentType (class (int-array 0))))

(defn get-setter [^Class class ^String name type]
  (let [type ({Boolean bool-type
               Integer int-type}
              type type)]
    (.getMethod class name ^objects (into-array [type]))))

(alter-var-root #'get-setter memoize)

(defn invoke-setter [obj ^Method method value]
  (.invoke method obj ^objects (to-array [value])))

(defn configure-connectors-reducer [connectors [the-name value]]
  (let [category (when-let [n (namespace the-name)]
                   (symbol n))]
    (if (= 'ring category)
      connectors
      (let [connectors (if (contains? connectors category)
                         connectors
                         (assoc connectors
                           category (create-connector category)))
            connector (get connectors category)
            method-name (->method-name the-name)]
        (try
          (let [method (get-setter (.getClass connector)
                                   method-name
                                   (.getClass value))]
            (invoke-setter connector method value))
          (catch Exception e
            (throw
             (IllegalArgumentException.
              (str "Failed to set " the-name " to " value)
              e))))
        connectors))))

(defn configure-connectors [options]
  (reduce configure-connectors-reducer {} options))

(defn ^Server run-jetty
  "Serve the given handler according to the options.
  Options:
    :ring/configurator   - A function called with the Server instance.
    :port
    :host
    :ring/join?          - Block the caller: defaults to true.
    :ssl/port
    :ssl/keystore
    :ssl/key-password
    :ssl/truststore
    :ssl/trust-password"
  [handler options]
  (let [options (merge {:port 80} options)
        server (doto (Server.) (.setSendDateHeader true))]
    (doseq [connector (vals (configure-connectors options))]
      (.addConnector server connector))
    (when-let [configurator (:ring/configurator options)]
      (configurator server))
    (doto server
      (.addHandler (@#'jetty/proxy-handler handler))
      (.start))
    (when (:ring/join? options true)
      (.join server))
    server))
