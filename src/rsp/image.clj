(ns rsp.image
  (:import
   (java.awt.image BufferedImageOp)
   [java.io File]
   (javax.imageio ImageIO ImageWriteParam)
   (javax.imageio.plugins.jpeg JPEGImageWriteParam)
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

(defn get-image-writers
  []
  (.next (ImageIO/getImageWritersByFormatName "jpg")))

(defn get-jpeg-quality
  []
  (let [jpeg-params (JPEGImageWriteParam. nil)]
    (.setCompressionMode jpeg-params ImageWriteParam/MODE_EXPLICIT)
    (.setCompressionQuality jpeg-params 1.0)
    jpeg-params))


