(ns dev
  (:require [babashka.fs :as fs]
            [rsp.image :as image])
  (:import (java.io File)
           (javax.imageio IIOImage ImageIO)
           (javax.imageio.stream FileImageOutputStream)
           (org.imgscalr Scalr Scalr$Method Scalr$Mode)))
(defn process-local
      [dir-name ^String sign size]
      (let [image (ImageIO/read (File. sign))
            new-width (image/get-image-width size)
            image-id (fs/strip-ext (fs/file-name sign))
            resized (Scalr/resize image Scalr$Method/ULTRA_QUALITY Scalr$Mode/FIT_TO_WIDTH new-width 0 image/antialias-op)
            writer (image/get-image-writers)
            output-dir (fs/path dir-name image-id)
            output-name (fs/path output-dir (str image-id "_" (image/get-image-name size) ".jpg"))]

               (fs/create-dirs output-dir)
               (.setOutput writer (FileImageOutputStream. (File. (str output-name))))
               (.write writer nil (IIOImage. resized nil nil) (image/get-jpeg-quality))
               (.dispose writer)))

(defn run-local
      [dir-name]
      (let [signs (fs/glob dir-name "*.jpg")]
               (doseq [sign signs]
                             (doseq [size (keys image/image-size)]
                               (process-local dir-name (str sign) size))))

