(ns rsp.image
  (:import (java.awt.image BufferedImageOp)
           (javax.imageio ImageIO ImageWriteParam)
           (javax.imageio.plugins.jpeg JPEGImageWriteParam)
           (org.imgscalr Scalr)))


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

