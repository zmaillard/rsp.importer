(ns rsp.importer
  (:require
   [clojure.tools.cli :refer [parse-opts]]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials]
   [next.jdbc :as jdbc]
   [next.jdbc.date-time]
   [rsp.config :as cfg]
   [rsp.postprocess :as postprocess]
   [rsp.staging :as staging]))


(defn s3-client
  []
  (let [config (cfg/load-config)]
    (aws/client {:api                  :s3
                 :region               "us-east-1"
                 :credentials-provider (credentials/basic-credentials-provider (cfg/get-aws-access config))
                 :endpoint-override    {
                                        :hostname (cfg/get-aws-endpoint-url config)
                                        :region   "auto"}})))

(def app-specs [["-a" "--ai" "Import AI Processed Images" :default false]])

(defn -main
  [& args]
  (let [{{ai :ai} :options } (parse-opts args app-specs)
        mode (if ai "ai/" "staging/")
        process-fn (if ai (partial postprocess/process) (partial staging/process))
        {signs :Contents} (aws/invoke (s3-client) {:op      :ListObjectsV2
                                                   :request {:Bucket "sign" :Prefix mode}})
        config (cfg/load-config)
        ds (jdbc/get-datasource (cfg/get-db-spec config))]
    (println mode)
    (with-open [conn (jdbc/get-connection ds)]
      (doseq [sign signs]
        (println sign)
        (process-fn conn sign)))))


