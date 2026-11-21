(ns rsp.postprocess
  (:require
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [rsp.image :as image]
   [rsp.cloud :as cloud]))

(def post-processed-key "edited")

(defn new-name
  ([key size type]
   (str key "/" post-processed-key "/" key "_" (image/get-image-name size) "." type))
  ([key size]
   (str key "/" post-processed-key "/" key "_" (image/get-image-name size) ".jpg"))
  ([key]
   (str key "/" post-processed-key "/" key ".jpg")))

(defn new-name-no-scale
  ([key type]
   (str key "/" post-processed-key "/" key "." type))
  ([key]
   (str key "/" post-processed-key "/" key ".jpg")))

(defn update-image
  [conn key]
  (let
    [imageid (bigint key)
     update {:update  [:'sign.highwaysign]
             :set {:has_processed true}
             :where  [:= :imageid imageid]}
     parsed-update (sql/format update)]

    (jdbc/execute-one! conn parsed-update)))

(defn extract-image-id
 [s]
 (when-let [match (re-find #"\d+" s)]
   match))

(defn process
  [conn {key :Key}]
  (if-let [image-id (extract-image-id key)]
    (let [image-name (cloud/download-image key image-id)
          raw-image (image/load-image image-name)]

      (doseq [size (keys image/image-size)]
        (cloud/scale-image (new-name image-id size "jpg") raw-image size "jpg")
        (cloud/scale-image (new-name image-id size "avif") raw-image size "avif")
        (cloud/scale-image (new-name image-id size "webp") raw-image size "webp"))

      (cloud/convert-image (new-name-no-scale image-id "avif") raw-image "avif")
      (cloud/convert-image (new-name-no-scale image-id "webp") raw-image "webp")
      (cloud/copy-image key (new-name-no-scale image-id))
      (update-image conn image-id)
      (cloud/delete-image key)
      (println (str "Imported " key " to " image-id)))
    (println (str "Skipping " key " - unable to extract image ID"))))

