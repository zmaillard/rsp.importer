(ns rsp.importer
  (:require [clojure.java.io :as io]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time]
            [rsp.config :as cfg]
            [exif-processor.core :refer [exif-for-filename]]
            [honey.sql :as sql])
  (:import [de.mkammerer.snowflakeid SnowflakeIdGenerator]
           (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream File)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)
           (org.imgscalr Scalr Scalr$Method Scalr$Mode)
           [javax.imageio ImageIO]))

(defonce snowflake-generator (SnowflakeIdGenerator/createDefault 0))

(defn s3-client
  []
  (let [config (cfg/load-config)]
    (aws/client {:api                  :s3
                 :region               "us-east-1"
                 :credentials-provider (credentials/basic-credentials-provider (cfg/get-aws-access config))
                 :endpoint-override    {
                                        :hostname (cfg/get-aws-endpoint-url config)
                                        :region   "auto"
                                        }})))

(def image-size {:placeholder
                 {:size 10 :suffix "p"}
                 :thumbnail
                 {:size 150 :suffix "t"}
                 :small
                 {:size 240 :suffix "s"}
                 :medium
                 {:size 500 :suffix "m"}
                 :large {:size 1024 :suffix "l"}})

(defn get-image-width
  [image]
  ((get image-size image) :size))

(defn get-image-name
  [image]
  ((get image-size image) :suffix))

(defn new-name
  ([key size]
   (str  key "/" key "_" (get-image-name size) ".jpg"))
  ([key]
   (str key "/" key ".jpg"))
  )

(defn delete-image
  [key]
  (-> (s3-client)
      (aws/invoke {:op :DeleteObject :request {:Bucket "sign" :Key key}})))

(defn copy-image
  [old-key new-key]
  (let [old-key-bucket-prefix (str "/sign/" old-key)]
    (-> (s3-client)
        (aws/invoke {:op :CopyObject :request {:Bucket "sign" :Key new-key :CopySource old-key-bucket-prefix}})
        )))

(defn scale-image
  [title image size]
  (let [new-width (get-image-width size)
        new-image (Scalr/resize image Scalr$Method/ULTRA_QUALITY Scalr$Mode/FIT_TO_WIDTH new-width 0 nil)
        image-output-stream (ByteArrayOutputStream.)
        ]
    (ImageIO/write new-image "jpg" image-output-stream)
    (-> (s3-client)
        (aws/invoke {:op :PutObject :request {:Bucket "sign" :ContentType "image/jpeg" :Key title :Body (.toByteArray image-output-stream)}})
        )))

(defn download-image
  [key image-id]
  (let [image-name (str "/tmp/" image-id ".jpg")
        file (File. image-name)]
    (-> (s3-client)
        (aws/invoke {:op :GetObject :request {:Bucket "sign" :Key key}})
        (:Body)
        (io/copy (io/output-stream file))
        )
    image-name))


(defn load-image
  [^String file-name]
  (let [file (File. file-name)]
    (ImageIO/read file)))

(defn get-dimensions
  [^BufferedImage image]
  (let [width (.getWidth image)
        height (.getHeight image)]
    {:width width :height height}))

(defn save-image
  [{date "Date/Time Original"} {width :width height :height} conn key]
  (let [dt (LocalDateTime/parse date (DateTimeFormatter/ofPattern "u:M:d k:m:s"))
        insert {:insert-into [:'sign.highwaysign_staging]
                :columns     [:image_width :image_height :imageid :date_taken]
                :values      [[width height key dt]]}
        parsed-insert (sql/format insert)]

    (jdbc/execute-one! conn parsed-insert)))

(defn process
  [conn {key :Key}]
  (let [image-id (.next snowflake-generator)
        image-name (download-image key image-id)
        metadata (exif-for-filename image-name)
        raw-image (load-image image-name)
        ]
    (doseq [size (keys image-size)]
      (scale-image (new-name image-id size) raw-image size)
      )
    (copy-image key (new-name image-id))
    (save-image metadata (get-dimensions raw-image) conn image-id)
    (delete-image key)
    (println (str "Imported " key " to " image-id))
    ))

(defn run
  [_]
  (let [{signs :Contents} (aws/invoke (s3-client) {:op      :ListObjectsV2
                                                   :request {:Bucket "sign" :Prefix "staging/"}})
        config (cfg/load-config)
        ds (jdbc/get-datasource (cfg/get-db-spec config))]
    (with-open [conn (jdbc/get-connection ds)]
      (doseq [sign signs]
        (process conn sign ))
      )))


