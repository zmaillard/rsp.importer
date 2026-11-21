(ns rsp.image
  (:import
   (java.awt.image BufferedImageOp)
   [java.io File]
   (javax.imageio IIOImage ImageIO ImageWriteParam)
   (javax.imageio.plugins.jpeg JPEGImageWriteParam)
   (javax.imageio.stream MemoryCacheImageOutputStream)
   (java.io ByteArrayOutputStream)
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

(defn get-writer-format
  "Maps a logical image type to the ImageIO format name used to look up a
  writer. Most types match 1:1, but \"avif\" is written via the \"heif\"
  ImageIO format (forced to AV1 compression in get-quality) since
  NightMonkeys only registers a single \"heif\" writer."
  [type]
  (case type
    "avif" "heif"
    type))

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
    "webp" (let [writer (get-image-writer-by-type (get-writer-format "webp"))
                 webp-params (.getDefaultWriteParam writer)]
             (.setCompressionQuality webp-params 1.0)
             webp-params)
    "avif" (let [writer (get-image-writer-by-type (get-writer-format "avif"))
                 ^HeifWriteParam avif-params (.getDefaultWriteParam writer)] 
             (.setCompressionMode avif-params ImageWriteParam/MODE_EXPLICIT)
             (.setCompressionType avif-params "HEIF_COMPRESSION_AV1")
             (.setCompressionQuality avif-params 1.0)
             avif-params)
    (throw (ex-info (str "Unsupported image type: " type) {:type type}))))

(defn encode-image
  "Encodes a BufferedImage to a byte array for the given logical image
  type (\"jpg\", \"webp\", or \"avif\"), using the writer and quality
  settings appropriate for that type."
  [image type]
  (let [writer (get-image-writer-by-type (get-writer-format type))
        image-output-stream (ByteArrayOutputStream.)
        memory-output-stream (MemoryCacheImageOutputStream. image-output-stream)]
    (try
      (.setOutput writer memory-output-stream)
      (.write writer nil (IIOImage. image nil nil) (get-quality type))
      ;; Not every ImageWriter flushes the output stream itself (e.g. the
      ;; HEIF writer doesn't), and MemoryCacheImageOutputStream buffers
      ;; internally until flushed, so without this the underlying
      ;; ByteArrayOutputStream can come back empty.
      (.flush memory-output-stream)
      (.toByteArray image-output-stream)
      (finally
        (.dispose writer)))))


