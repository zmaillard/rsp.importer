(ns rsp.importer
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [next.jdbc :as jdbc]
            [next.jdbc.date-time]
            [rsp.config :as cfg]
            [rsp.image :as image]
            [exif-processor.core :refer [exif-for-filename]]
            [honey.sql :as sql])
  (:import [de.mkammerer.snowflakeid SnowflakeIdGenerator]
           (java.awt.image BufferedImage)
           (javax.imageio IIOImage ImageIO)
           (java.io ByteArrayOutputStream File)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)
           (javax.imageio.stream FileImageOutputStream)
           (org.imgscalr Scalr Scalr$Method Scalr$Mode)))

(defonce snowflake-generator (SnowflakeIdGenerator/createDefault 0))

(defn s3-client
  []
  (let [config (cfg/load-config)]
    (aws/client {:api                  :s3
                 :region               "us-east-1"
                 :credentials-provider (credentials/basic-credentials-provider (cfg/get-aws-access config))
                 :endpoint-override    {
                                        :hostname (cfg/get-aws-endpoint-url config)
                                        :region   "auto"}})))



(defn new-name
  ([key size]
   (str key "/" key "_" (image/get-image-name size) ".jpg"))
  ([key]
   (str key "/" key ".jpg")))

(defn delete-image
  [key]
  (-> (s3-client)
      (aws/invoke {:op :DeleteObject :request {:Bucket "sign" :Key key}})))

(defn copy-image
  [old-key new-key]
  (let [old-key-bucket-prefix (str "/sign/" old-key)]
    (-> (s3-client)
        (aws/invoke {:op :CopyObject :request {:Bucket "sign" :Key new-key :CopySource old-key-bucket-prefix}}))))


(defn scale-image
  [title image size]
  (let [new-width (image/get-image-width size)
        new-image (Scalr/resize image Scalr$Method/ULTRA_QUALITY Scalr$Mode/FIT_TO_WIDTH new-width 0 image/antialias-op)
        writer (image/get-image-writers)
        image-output-stream (ByteArrayOutputStream.)]
    (.setOutput writer image-output-stream)
    (.write writer nil (IIOImage. new-image nil nil) (image/get-jpeg-quality))
    (-> (s3-client)
        (aws/invoke {:op :PutObject :request {:Bucket "sign" :ContentType "image/jpeg" :Key title :Body (.toByteArray image-output-stream)}}))
    (.dispose writer)))

(defn download-image
  [key image-id]
  (let [image-name (str "/tmp/" image-id ".jpg")
        file (File. image-name)]
    (-> (s3-client)
        (aws/invoke {:op :GetObject :request {:Bucket "sign" :Key key}})
        (:Body)
        (io/copy (io/output-stream file)))

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


(defn build-decimal-degrees
  [deg]
  (let [[d m s](-> deg
                   (str/replace #"[°|\'|\"]" "")
                   (str/split #" ")
                   (as-> items (map #(Double/parseDouble %) items)))
        neg (if (< d 0) -1 1)]
    (* neg(+ (abs d) (/ m 60) (/ s 3600)))))

(defn parse-lat-long
  [latitude  longitude]
  (if (or (nil? latitude) (nil? longitude))
    {:latitude nil :longitude nil}
    {:latitude (build-decimal-degrees latitude) :longitude (build-decimal-degrees longitude)}))

(defn save-image
  [{date "Date/Time Original" lat "GPS Latitude", lng "GPS Longitude"} {width :width height :height} conn key]
  (let [dt (LocalDateTime/parse date (DateTimeFormatter/ofPattern "u:M:d k:m:s"))
        dec-degrees (parse-lat-long lat lng)
        insert {:insert-into [:'sign.highwaysign_staging]
                :columns     [:image_width :image_height :imageid :date_taken :latitude :longitude]
                :values      [[width height key dt (:latitude dec-degrees) (:longitude dec-degrees)]]}
        parsed-insert (sql/format insert)]

    (jdbc/execute-one! conn parsed-insert)))


(defn process
  [conn {key :Key}]
  (let [image-id (.next snowflake-generator)
        image-name (download-image key image-id)
        metadata (exif-for-filename image-name)
        raw-image (load-image image-name)]

    (doseq [size (keys image/image-size)]
      (scale-image (new-name image-id size) raw-image size))

    (copy-image key (new-name image-id))
    (save-image metadata (get-dimensions raw-image) conn image-id)
    (delete-image key)
    (println (str "Imported " key " to " image-id))))


(defn -main
  [_]
  (let [{signs :Contents} (aws/invoke (s3-client) {:op      :ListObjectsV2
                                                   :request {:Bucket "sign" :Prefix "staging/"}})
        config (cfg/load-config)
        ds (jdbc/get-datasource (cfg/get-db-spec config))]
    (with-open [conn (jdbc/get-connection ds)]
      (doseq [sign signs]
        (process conn sign)))))


