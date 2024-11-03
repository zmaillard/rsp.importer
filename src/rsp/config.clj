(ns rsp.config
  (:require [clojure.java.io :as io]
            [aero.core :as aero]))

(defn get-db-spec
  [config]
  {:jdbcUrl  (get-in config [:db :jdbc-url])
             :user (get-in config [:db :username])
             :password (get-in config [:db :password])})

(defn get-aws-access
  [config]
  {:access-key-id     (get-in config [:aws :access-key])
   :secret-access-key (get-in config [:aws :secret-key])})



(defn get-aws-endpoint-url
  [config]
  (get-in config [:aws :endpoint-url]))

(defn load-config
  []
  (-> "config.edn"
      (io/resource)
      (aero/read-config)))
