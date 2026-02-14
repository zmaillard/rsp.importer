(ns rsp.staging
  (:require  [clojure.string :as str]
             [next.jdbc :as jdbc]
             [next.jdbc.date-time]
             [rsp.cloud :as cloud]
             [rsp.image :as image]
             [exif-processor.core :refer [exif-for-filename]]
             [honey.sql :as sql])
  (:import [de.mkammerer.snowflakeid SnowflakeIdGenerator]
           (java.awt.image BufferedImage)
           (java.time LocalDateTime)
           (java.time.format DateTimeFormatter)))

(defonce snowflake-generator (SnowflakeIdGenerator/createDefault 0))

(defn new-name
  ([key size]
   (str key "/" key "_" (image/get-image-name size) ".jpg"))
  ([key]
   (str key "/" key ".jpg")))

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
        image-name (cloud/download-image key image-id)
        metadata (exif-for-filename image-name)
        raw-image (image/load-image image-name)]

    (doseq [size (keys image/image-size)]
      (cloud/scale-image (new-name image-id size) raw-image size))

    (cloud/copy-image key (new-name image-id))
    (save-image metadata (get-dimensions raw-image) conn image-id)
    (cloud/delete-image key)
    (println (str "Imported " key " to " image-id))))

