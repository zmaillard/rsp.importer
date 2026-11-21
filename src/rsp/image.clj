(ns rsp.image
  (:import
   (java.awt.image BufferedImageOp)
   [java.io File]
   (javax.imageio ImageIO ImageWriteParam)
   (javax.imageio.plugins.jpeg JPEGImageWriteParam)
   (com.github.gotson.nightmonkeys.heif.imageio.plugins HeifWriteParam)
   (org.imgscalr Scalr)))


(def image-size {:placeholder
                 {:size 10 :suffix "p"}
                 :thumbnail
                 {:size 150 :suffix "t"}
                 :small
                 {:size 240 :suffix "s"}
                 :medium
                 {:size 500 :suffix "m"}
                 :large {:size 1024 :suffix "l"}})

(defn load-image
  [^String file-name]
  (let [file (File. file-name)]
    (ImageIO/read file)))

(defn get-image-width
  [image]
  ((get image-size image) :size))

(defn get-image-name
  [image]
  ((get image-size image) :suffix))

(def antialias-op (into-array BufferedImageOp [Scalr/OP_ANTIALIAS]))

(defn get-image-writer-by-type
  [type]
  (.next (ImageIO/getImageWritersByFormatName type)))


(defn get-quality 
  [type]
  (case type
    "jpg"  (let [jpeg-params (JPEGImageWriteParam. nil)]
             (.setCompressionMode jpeg-params ImageWriteParam/MODE_EXPLICIT)
             (.setCompressionQuality jpeg-params 1.0)
             jpeg-params)
    "webp" (let [writer (get-image-writer-by-type "webp")
                 webp-params (.getDefaultWriteParam writer)]
             (.setCompressionQuality webp-params 1.0)
             webp-params)
    "avif" (let [writer (get-image-writer-by-type "heif")
                 ^HeifWriteParam avif-params (.getDefaultWriteParam writer)] 
             (.setCompressionMode avif-params ImageWriteParam/MODE_EXPLICIT)
             (.setCompressionType avif-params "HEIF_COMPRESSION_AV1")
             (.setCompressionQuality avif-params 1.0)
             avif-params)
    (throw (ex-info (str "Unsupported image type: " type) {:type type}))))


