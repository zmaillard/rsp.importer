(ns rsp.cloud
  (:require
   [clojure.java.io :as io]
   [cognitect.aws.client.api :as aws]
   [cognitect.aws.credentials :as credentials]
   [next.jdbc.date-time]
   [rsp.config :as cfg]
   [rsp.image :as image])
  (:import
   (java.io ByteArrayOutputStream File)
   (javax.imageio IIOImage)
   (javax.imageio.stream MemoryCacheImageOutputStream)
   (org.imgscalr Scalr Scalr$Method Scalr$Mode)))

(defn s3-client
  []
  (let [config (cfg/load-config)]
    (aws/client {:api                  :s3
                 :region               "us-east-1"
                 :credentials-provider (credentials/basic-credentials-provider (cfg/get-aws-access config))
                 :endpoint-override    {
                                        :hostname (cfg/get-aws-endpoint-url config)
                                        :region   "auto"}})))
(defn scale-image
  [title image size]
  (let [new-width (image/get-image-width size)
        new-image (Scalr/resize image Scalr$Method/ULTRA_QUALITY Scalr$Mode/FIT_TO_WIDTH new-width 0 image/antialias-op)
        writer (image/get-image-writers)
        image-output-stream (ByteArrayOutputStream.)
        memory-output-stream (MemoryCacheImageOutputStream. image-output-stream)]
    (.setOutput writer memory-output-stream)
    (.write writer nil (IIOImage. new-image nil nil) (image/get-jpeg-quality))
    (-> (s3-client)
        (aws/invoke {:op :PutObject :request {:Bucket "sign" :ContentType "image/jpeg" :Key title :Body (.toByteArray image-output-stream)}}))
    (.dispose writer)))

(defn delete-image
  [key]
  (-> (s3-client)
      (aws/invoke {:op :DeleteObject :request {:Bucket "sign" :Key key}})))

(defn copy-image
  [old-key new-key]
  (let [old-key-bucket-prefix (str "/sign/" old-key)]
    (-> (s3-client)
        (aws/invoke {:op :CopyObject :request {:Bucket "sign" :Key new-key :CopySource old-key-bucket-prefix}}))))


(defn download-image
  [key image-id]
  (let [image-name (str "/tmp/" image-id ".jpg")
        file (File. image-name)]
    (-> (s3-client)
        (aws/invoke {:op :GetObject :request {:Bucket "sign" :Key key}})
        (:Body)
        (io/copy (io/output-stream file)))

    image-name))

