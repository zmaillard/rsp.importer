(ns rsp.image-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [rsp.image :as image])
  (:import
   (java.awt.image BufferedImage)
   (java.io ByteArrayInputStream)
   (javax.imageio ImageIO ImageWriteParam)
   (javax.imageio.plugins.jpeg JPEGImageWriteParam)
   (com.github.gotson.nightmonkeys.heif.imageio.plugins HeifWriteParam)
   (com.luciad.imageio.webp WebPWriteParam)))

;; --- Tier 1: pure logic -----------------------------------------------

(deftest get-image-width-test
  (testing "returns the configured pixel size for every known size key"
    (is (= 10 (image/get-image-width :placeholder)))
    (is (= 150 (image/get-image-width :thumbnail)))
    (is (= 240 (image/get-image-width :small)))
    (is (= 500 (image/get-image-width :medium)))
    (is (= 1024 (image/get-image-width :large)))))

(deftest get-image-name-test
  (testing "returns the configured filename suffix for every known size key"
    (is (= "p" (image/get-image-name :placeholder)))
    (is (= "t" (image/get-image-name :thumbnail)))
    (is (= "s" (image/get-image-name :small)))
    (is (= "m" (image/get-image-name :medium)))
    (is (= "l" (image/get-image-name :large)))))

(deftest get-writer-format-test
  (testing "avif is written via the heif ImageIO format name"
    (is (= "heif" (image/get-writer-format "avif"))))
  (testing "other types pass through unchanged"
    (is (= "jpg" (image/get-writer-format "jpg")))
    (is (= "webp" (image/get-writer-format "webp")))))

(deftest get-quality-jpg-test
  (let [params (image/get-quality "jpg")]
    (is (instance? JPEGImageWriteParam params))
    (is (= ImageWriteParam/MODE_EXPLICIT (.getCompressionMode params)))
    (is (= 1.0 (.getCompressionQuality params)))))

(deftest get-quality-webp-test
  (let [params (image/get-quality "webp")]
    (is (instance? WebPWriteParam params))
    (is (= 1.0 (.getCompressionQuality params)))))

(deftest get-quality-avif-test
  ;; This is the single most important assertion in the test suite: it
  ;; pins the exact bug class that caused this whole feature to need
  ;; extensive debugging (wrong ImageIO format name, wrong compression
  ;; type silently falling back to a non-AV1 codec).
  (let [^HeifWriteParam params (image/get-quality "avif")]
    (is (instance? HeifWriteParam params))
    (is (= ImageWriteParam/MODE_EXPLICIT (.getCompressionMode params)))
    (is (= "HEIF_COMPRESSION_AV1" (.getCompressionType params)))
    (is (= 1.0 (.getCompressionQuality params)))))

(deftest get-quality-unsupported-type-test
  (let [ex (try (image/get-quality "png")
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= {:type "png"} (ex-data ex)))))

;; --- Tier 2: codec round-trip -------------------------------------------

(defn- make-test-image
  [w h]
  (BufferedImage. w h BufferedImage/TYPE_INT_RGB))

(deftest encode-image-round-trip-test
  (doseq [type ["jpg" "webp" "avif"]]
    (testing type
      (let [bytes (image/encode-image (make-test-image 16 16) type)]
        (is (pos? (count bytes)) "produced non-empty output")
        (let [decoded (ImageIO/read (ByteArrayInputStream. bytes))]
          (is (some? decoded) "ImageIO could read back what was written")
          (is (= 16 (.getWidth decoded)))
          (is (= 16 (.getHeight decoded))))))))
