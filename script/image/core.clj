(ns image.core
  (:require [babashka.fs :as fs])
  (:import  (java.time LocalDateTime)
            (java.time.format DateTimeFormatter)))

(defn coerce-imageid
 [key]
 (bigint key))


(defn save-image-parms
 [{:keys [date lat lng imageWidth imageHeight]} key]
 [imageWidth imageHeight key date lat lng])

(defn exif-output-path
 [base-dir image-id]
 (str (fs/path base-dir image-id) "-exif.json"))

(defn orig-metadata-path
 [base-dir image-id]
 (str (fs/path base-dir image-id) ".json"))

(def image-sizes-formats
  [{:suffix "" :width nil :formats [:avif :webp]}
   {:suffix "_l" :width 1024 :formats [:jpg :avif :webp]}
   {:suffix "_m" :width 500 :formats [:jpg :avif :webp]}
   {:suffix "_s" :width 240 :formats [:jpg :avif :webp]}
   {:suffix "_t" :width 150 :formats [:jpg :avif :webp]}
   {:suffix "_p" :width 10 :formats [:jpg :avif :webp]}])

(defn image-format-size-variants
 []
 (for [{:keys [suffix width formats]} image-sizes-formats
       format formats]
  {:suffix suffix :width width :format format}))

(defn image-target-dir
 [base-dir image-id]
 (fs/path base-dir image-id))

(defn image-resize-command
  [base-dir image-id f {:keys [suffix format width]}]
  (let [out-path (str (fs/path (image-target-dir base-dir image-id) (str image-id suffix "." (name format))))]
    (if width
      ["magick" (str f) "-resize" (str width "x") out-path]
      ["magick" (str f) out-path])))

(defn image-resize-commands
 [base-dir image-id f]
 (map #(image-resize-command base-dir image-id f %) (image-format-size-variants)))

(defn get-images
 "Get all jpg images in the given `path`."
 [path]
 (concat (fs/glob path "*.jpg") (fs/glob path "*.JPG")))

(defn build-decimal-degrees
  "Convert an EXIF GPS coordinate description `deg` to decimal degrees."
  [deg]
  (if (nil? deg)
    nil
   (let [[_ d m s dir](re-find #"(\d+) deg (\d+)' (\d+.\d+)\" ([N|S|E|W])" deg)
         neg (if (or(= dir "S")(= dir "W")) -1 1)]
     (* neg(+ (abs (Double/parseDouble d)) (/ (Double/parseDouble m) 60) (/ (Double/parseDouble s) 3600))))))

(defn parse-date
  "Convert `date` string from EXIF to a Clojure date."
  [date]
  (LocalDateTime/parse date (DateTimeFormatter/ofPattern "u:M:d k:m:s")))

(defn read-metadata
  "Read EXIF metadata from the given `metadata` map and return a map with the relevant fields."
  [metadata]
  (let [date (parse-date (get-in metadata [0 "DateTimeOriginal"]))
        imageWidth (get-in metadata [0 "ImageWidth"])
        imageHeight (get-in metadata [0 "ImageHeight"])
        lat (build-decimal-degrees (get-in metadata [0 "GPSLatitude"]))
        lng (build-decimal-degrees (get-in metadata [0 "GPSLongitude"]))]
    {:date date :lat lat :lng lng :imageWidth imageWidth :imageHeight imageHeight}))

