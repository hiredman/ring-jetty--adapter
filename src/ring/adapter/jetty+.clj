(ns ring.adapter.jetty+
  (:require [ring.adapter.jetty :as jetty])
  (:import (org.mortbay.jetty Server)
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
          (clojure.lang.Reflector/invokeInstanceMethod
           connector method-name (into-array [value]))
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
