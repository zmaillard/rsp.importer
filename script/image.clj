(ns image
  (:require [babashka.fs :as fs]
            [babashka.tasks :as tasks]
            [clojure.data.json :as json]  
            [pod.babashka.postgresql :as pg]
            [pod.zmaillard.snowflakeid :as snowflake]
            [image.core :as core]))

(def conn {:dbtype "postgres"
           :jdbcUrl (System/getenv "JDBC_URL")
           :user (System/getenv "DB_USERNAME")
           :password (System/getenv "DB_PASSWORD")})

(defn -update-image
  "Update the has_processed flag for an imageid corresponding to `key` in the
  sign.highwaysign table."
  [key]
  (let
    [imageid (core/coerce-imageid key)]
    (pg/execute-one! conn ["UPDATE sign.highwaysign SET has_processed = true WHERE imageid = ?" imageid])))

(defn -save-image
  [metadata key]
  (apply pg/execute-one! conn "INSERT INTO sign.highwaysign_staging (image_width, image_height, imageid, date_taken, latitude, longitude) VALUES (?, ?, ?, ?, ?, ?)" (core/save-image-parms metadata key)))


(defn exif
  [{base-dir :path}]
  (doseq [ f (core/get-images base-dir)]
    (let [base (fs/file-name f)
          [image-id _] (fs/split-ext base)]
      (tasks/shell {:out (core/exif-output-path base-dir image-id)} "exiftool" "-json" f))))

(defn generate-ids
  [{base-dir :path}]
  (let [images (core/get-images base-dir)
        snowflakeIds (snowflake/new-id (count images))
        combined (map vector snowflakeIds images)]
      (doseq [[id f] combined]
         (let [new-path (fs/path base-dir (str id ".jpg"))
               new-metadata-path  (fs/path base-dir (str id ".json"))
               oldfile (fs/file-name f)]
              (spit (str id ".json") (json/write-str {:original oldfile} :append true))
              (fs/move (str id ".json") new-metadata-path)
              (fs/move f  new-path)))))


(defn import-new
  [{base-dir :path}]
 (doseq [f (core/get-images base-dir)]
   (let [base (fs/file-name f)
         [image-id _] (fs/split-ext base)
         exif-path (core/exif-output-path base-dir image-id)
         orig-path (core/orig-metadata-path base-dir image-id)
         metadata (core/read-metadata(json/read-str (slurp exif-path))) 
         orig-file (get (json/read-str (slurp orig-path)) "original")] 
    (prn "Importing image" image-id "with metadata" metadata)
    (tasks/shell "rclone copy" "--dry-run" "-vv" (fs/absolutize(fs/path base-dir image-id)) (str "r2:/sign/" image-id)) 
    (-save-image metadata image-id)
    (tasks/shell "rclone deletefile" "--dry-run" (str "r2:sign/staging/" orig-file)))))

(defn import-edited
  [{base-dir :path}]
 (doseq [f (core/get-images base-dir)]
   (let [base (fs/file-name f)
         [image-id _] (fs/split-ext base)]
    (prn "Updated image" image-id "with edited")
    (tasks/shell "rclone copy" (fs/absolutize(fs/path base-dir image-id)) (str "r2:/sign/" image-id "/edited")) 
    (-update-image image-id)
    (tasks/shell "rclone deletefile"  (str "r2:sign/ai/" image-id ".jpg")))))


(defn resize-images 
  [{base-dir :path}]

  (prn "Resizing images in" base-dir)

  (doseq [ f (core/get-images base-dir)]
    (let [base (fs/file-name f)
          [image-id _] (fs/split-ext base)
          target-dir (fs/path base-dir image-id)]

      (if (not (fs/exists? target-dir))
        (fs/create-dir target-dir)
        (prn "Directory already exists for" image-id))

     ; TODO:: fs/copy fails if file already exists at that path
     (fs/copy f (fs/path base-dir image-id (str image-id ".jpg")))

     (doseq [cmd (core/image-resize-commands base-dir image-id f)]
       (apply tasks/shell cmd)))))

