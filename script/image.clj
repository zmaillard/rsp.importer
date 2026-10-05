(ns image
  (:require [babashka.fs :as fs]
            [babashka.tasks :as tasks]
            [clojure.data.json :as json]  
            [pod.babashka.postgresql :as pg]
            [pod.zmaillard.snowflakeid :as snowflake])
  (:import  (java.time LocalDateTime)
            (java.time.format DateTimeFormatter)))

(def conn {:dbtype "postgres"
           :jdbcUrl (System/getenv "JDBC_URL")
           :user (System/getenv "DB_USERNAME")
           :password (System/getenv "DB_PASSWORD")})

(defn -update-image
  [key]
  (let
    [imageid (bigint key)]
    (pg/execute-one! conn ["UPDATE sign.highwaysign SET has_processed = true WHERE imageid = ?" imageid])))

(defn -save-image
  [{date :date lat :lat lng :lng imageWidth :imageWidth imageHeight :imageHeight} conn key]
  (pg/execute-one! conn "INSERT INTO sign.highwaysign_staging (image_width, image_height, imageid, date_taken, latitude, longitude) VALUES (?, ?, ?, ?, ?, ?)" imageWidth imageHeight key date lat lng))

(defn -get-images
 [path]
 (concat (fs/glob path "*.jpg") (fs/glob path "*.JPG")))

(defn -build-decimal-degrees
  [deg]
  (if (nil? deg)
    nil
   (let [[_ d m s dir](re-find #"(\d+) deg (\d+)' (\d+.\d+)\" ([N|S|E|W])" deg)
         neg (if (or(= dir "S")(= dir "W")) -1 1)]
     (* neg(+ (abs (Double/parseDouble d)) (/ (Double/parseDouble m) 60) (/ (Double/parseDouble s) 3600))))))

(defn -parse-date
  [date]
  (LocalDateTime/parse date (DateTimeFormatter/ofPattern "u:M:d k:m:s")))

(defn -read-metadata
  [metadata]
  (let [date (-parse-date (get-in metadata [0 "DateTimeOriginal"]))
        imageWidth (get-in metadata [0 "ImageWidth"])
        imageHeight (get-in metadata [0 "ImageHeight"])
        lat (-build-decimal-degrees (get-in metadata [0 "GPSLatitude"]))
        lng (-build-decimal-degrees (get-in metadata [0 "GPSLongitude"]))]
    {:date date :lat lat :lng lng :imageWidth imageWidth :imageHeight imageHeight}))


(defn exif
  [{base-dir :path}]
  (doseq [ f (-get-images base-dir)]
    (let [base (fs/file-name f)
          [image-id _] (fs/split-ext base)]
      (tasks/shell {:out (str (fs/path base-dir image-id) "-exif.json")} "exiftool" "-json" f))))

(defn generate-ids
  [{base-dir :path}]
  (let [images (-get-images base-dir)
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
 (doseq [f (-get-images base-dir)]
   (let [base (fs/file-name f)
         [image-id _] (fs/split-ext base)
         exif-path (str (fs/path base-dir image-id) "-exif.json")
         orig-path (str (fs/path base-dir image-id) ".json")
         metadata (-read-metadata(json/read-str (slurp exif-path))) 
         orig-file (get (json/read-str (slurp orig-path)) "original")] 
    (prn "Importing image" image-id "with metadata" metadata)
    (tasks/shell "rclone copy" "--dry-run" "-vv" (fs/absolutize(fs/path base-dir image-id)) (str "r2:/sign/" image-id)) 
    ;(save-image metadata conn image-id)
    (tasks/shell "rclone deletefile" "--dry-run" (str "r2:sign/staging/" orig-file)))))

(defn import-edited
  [{base-dir :path}]
 (doseq [f (-get-images base-dir)]
   (let [base (fs/file-name f)
         [image-id _] (fs/split-ext base)]
    (prn "Updated image" image-id "with edited")
    (tasks/shell "rclone copy" (fs/absolutize(fs/path base-dir image-id)) (str "r2:/sign/" image-id "/edited")) 
    (-update-image image-id)
    (tasks/shell "rclone deletefile"  (str "r2:sign/ai/" image-id ".jpg")))))


(defn resize-images 
  [{base-dir :path}]

  (prn "Resizing images in" base-dir)

  (doseq [ f (-get-images base-dir)]
    (let [base (fs/file-name f)
          [image-id _] (fs/split-ext base)]

      (if (not (fs/exists? (fs/path base-dir image-id)))
        (fs/create-dir (fs/path base-dir  image-id))
        (prn "Directory already exists for" image-id))

     ; TODO:: fs/copy fails if file already exists at that path
     (fs/copy f (fs/path base-dir image-id (str image-id ".jpg")))
     (tasks/shell "magick" (str f) (fs/path base-dir image-id (str image-id ".avif")))
     (tasks/shell "magick" (str f) (fs/path base-dir image-id (str image-id ".webp")))
     (tasks/shell "magick" (str f) "-resize" "1024x" (fs/path base-dir image-id (str image-id "_l.jpg")))
     (tasks/shell "magick" (str f) "-resize" "1024x" (fs/path base-dir image-id (str image-id "_l.avif")))
     (tasks/shell "magick" (str f) "-resize" "1024x" (fs/path base-dir image-id (str image-id "_l.webp")))
     (tasks/shell "magick" (str f) "-resize" "500x" (fs/path base-dir image-id (str image-id "_m.jpg")))
     (tasks/shell "magick" (str f) "-resize" "500x" (fs/path base-dir image-id (str image-id "_m.avif")))
     (tasks/shell "magick" (str f) "-resize" "500x" (fs/path base-dir image-id (str image-id "_m.webp")))
     (tasks/shell "magick" (str f) "-resize" "240x" (fs/path base-dir image-id (str image-id "_s.jpg")))
     (tasks/shell "magick" (str f) "-resize" "240x" (fs/path base-dir image-id (str image-id "_s.avif")))
     (tasks/shell "magick" (str f) "-resize" "240x" (fs/path base-dir image-id (str image-id "_s.webp")))
     (tasks/shell "magick" (str f) "-resize" "150x" (fs/path base-dir image-id (str image-id "_t.jpg")))
     (tasks/shell "magick" (str f) "-resize" "150x" (fs/path base-dir image-id (str image-id "_t.avif")))
     (tasks/shell "magick" (str f) "-resize" "150x" (fs/path base-dir image-id (str image-id "_t.webp")))
     (tasks/shell "magick" (str f) "-resize" "10x" (fs/path base-dir image-id (str image-id "_p.jpg")))
     (tasks/shell "magick" (str f) "-resize" "10x" (fs/path base-dir image-id (str image-id "_p.avif")))
     (tasks/shell "magick" (str f) "-resize" "10x" (fs/path base-dir image-id (str image-id "_p.webp"))))))

