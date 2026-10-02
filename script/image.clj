(ns image
  (:require [babashka.fs :as fs]
            [babashka.tasks :as tasks]
            [clojure.data.json :as json]  
            [pod.snowflake :as snowflake])
 (:import (java.time LocalDateTime)
          (java.time.format DateTimeFormatter)))

(defn build-decimal-degrees
  [deg]
  (if (nil? deg)
    nil
   (let [[_ d m s dir](re-find #"(\d+) deg (\d+)' (\d+.\d+)\" ([N|S|E|W])" deg)
         neg (if (or(= dir "S")(= dir "W")) -1 1)]
     (* neg(+ (abs (Double/parseDouble d)) (/ (Double/parseDouble m) 60) (/ (Double/parseDouble s) 3600))))))

(defn parse-date
  [date]
  (LocalDateTime/parse date (DateTimeFormatter/ofPattern "u:M:d k:m:s")))

(defn read-metadata
  [metadata]
  (let [date (parse-date (get-in metadata [0 "DateTimeOriginal"]))
        imageWidth (get-in metadata [0 "ImageWidth"])
        imageHeight (get-in metadata [0 "ImageHeight"])
        lat (build-decimal-degrees (get-in metadata [0 "GPSLatitude"]))
        lng (build-decimal-degrees (get-in metadata [0 "GPSLongitude"]))]
    {:date date :lat lat :lng lng :imageWidth imageWidth :imageHeight imageHeight}))


(defn get-images
 [path]
 (concat (fs/glob path "*.jpg") (fs/glob path "*.JPG")))

(defn upload-images [image])

(defn import-new
  [{base-dir :path}]
 (doseq [f (get-images base-dir)]
   (let [base (fs/file-name f)
         [image-id _] (fs/split-ext base)
         exif-path (str (fs/path base-dir image-id) ".json")
         metadata (read-metadata(json/read-str (slurp exif-path))) 
         id (snowflake/new-id)]
    (prn metadata))))

(defn exif
  [{base-dir :path}]
  (doseq [ f (get-images base-dir)]
    (let [base (fs/file-name f)
          [image-id _] (fs/split-ext base)]
      (tasks/shell {:out (str (fs/path base-dir image-id) ".json")} "exiftool" "-json" f))))

; (defn image-size
;   [{base-dir :path}]
;   (doseq [f (get-images base-dir)]
;     (prn (str f))
;     (let [{dims :out} (tasks/shell {:out :string}  "identify" "-ping" "-format" "%[width] %[height]" (str f))]
;       (prn dims))))


(defn resize-images 
  [{base-dir :path}]

  (prn "Resizing images in" base-dir)

  (doseq [ f (get-images base-dir)]
    (let [base (fs/file-name f)
          [image-id _] (fs/split-ext base)]

      (if (not (fs/exists? (fs/path base-dir image-id)))
        (fs/create-dir (fs/path base-dir  image-id)))

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

