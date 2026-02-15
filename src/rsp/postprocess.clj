(ns rsp.postprocess
  (:require
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [rsp.image :as image]
   [rsp.cloud :as cloud]))

(def PostProcessedKey "edited")

(defn new-name
  ([key size]
   (str key "/" PostProcessedKey "/" key "_" (image/get-image-name size) ".jpg"))
  ([key]
   (str key "/" PostProcessedKey "/"  key ".jpg")))

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

  (let [image-id (extract-image-id key)
        image-name (cloud/download-image key image-id)
        raw-image (image/load-image image-name)]

    (doseq [size (keys image/image-size)]
      (cloud/scale-image (new-name image-id size) raw-image size))

    (cloud/copy-image key (new-name image-id))
    (update-image conn image-id)
    (cloud/delete-image key)
    (println (str "Imported " key " to " image-id))))

