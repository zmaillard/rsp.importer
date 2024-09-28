(ns rsp.importer
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [mikera.image.core :as image]
            [next.jdbc :as jdbc]
            [rsp.config :as cfg]
            [exif-processor.core :refer [exif-for-filename]]
            [honey.sql :as sql])
  (:import [de.mkammerer.snowflakeid SnowflakeIdGenerator]
           (java.io  ByteArrayOutputStream File)
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
   (str "temp/" key "/" key "_" (get-image-name size) ".jpg"))
  ([key]
   (str "temp/" key "/" key ".jpg"))
  )


(defn scale-image
  [title image size]
  (let [new-width (get-image-width size)
        new-image (image/resize image new-width)
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

;(defn load-image
;  [key]
;  (-> (s3-client)
;      (aws/invoke {:op :GetObject :request {:Bucket "sign" :Key key}})
;      (:Body)
;      (BufferedInputStream.)
;      (ImageIO/read)
;      ))
;


(defn load-image
  [^String file-name]
  (let [file (File. file-name)]
    (ImageIO/read file)))

(defn get-dimensions
  [image]
  (let [width (image/width image)
        height (image/height image)]
    {:width width :height height}))

(defn save-image
  [{date "Date/Time Original" }{width :width height :height} conn key]
    (-> {:insert-into [:highwaysign_staging]
         :columns     [:image_width :image_height :date_taken :imageid]
         :values      [[width height (java.util.Date.) key]]}
        (sql/format)
        (jdbc/execute! conn)
        ))

(defn process
  [conn {key :Key}]
  (let [image-id (.next snowflake-generator)
        image-name (download-image key image-id)
        metadata (exif-for-filename image-name)
        raw-image (load-image image-name)
        ]
    (println (string/join ";" (keys metadata) ))
    (println (get metadata "Date/Time Original"))
    (scale-image (new-name image-id :placeholder) raw-image :placeholder)
    (scale-image (new-name image-id :thumbnail) raw-image :thumbnail)
    (scale-image (new-name image-id :small) raw-image :small)
    (scale-image (new-name image-id :medium) raw-image :medium)
    (scale-image (new-name image-id :large) raw-image :large)
    (save-image metadata (get-dimensions raw-image) conn image-id)))

(defn run
  []
  (let [{signs :Contents} (aws/invoke (s3-client) {:op      :ListObjectsV2
                                                   :request {:Bucket "sign" :Prefix "staging/"}})
        config (cfg/load-config)
        ds (jdbc/get-datasource (cfg/get-db-spec config))]
    (with-open [conn (jdbc/get-connection ds)]
      (process conn (first signs))
      ;(map process signs)
      )))


; {Compression Type Baseline, Component 2 Cb component: Quantization table 1, Sampling factors 1 horiz/1 vert, Resolution Units none, Data Precision 8 bits, Component 1 Y component: Quantization table 0, Sampling factors 2 horiz/2 vert, Image Width 5152 pixels, Y Resolution 1 dot, Component 3 Cr component: Quantization table 1, Sampling factors 1 horiz/1 vert, Image Height 3864 pixels, X Resolution 1 dot, Thumbnail Width Pixels 0, Version 1.2, Number of Components 3, Thumbnail Height Pixels 0}
