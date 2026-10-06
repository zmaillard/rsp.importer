(ns image.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [image.core :as core])
  (:import (java.time LocalDateTime)))

(deftest build-decimal-degrees-test
  (testing "north latitude"
    (is (= 40.446266666666665 (core/build-decimal-degrees "40 deg 26' 46.56\" N"))))

  (testing "south latitude is negative"
    (is (= -40.446266666666665 (core/build-decimal-degrees "40 deg 26' 46.56\" S"))))

  (testing "east longitude"
    (is (= 79.9869 (core/build-decimal-degrees "79 deg 59' 12.84\" E"))))

  (testing "west longitude is negative"
    (is (= -79.9869 (core/build-decimal-degrees "79 deg 59' 12.84\" W"))))

  (testing "nil input returns nil"
    (is (nil? (core/build-decimal-degrees nil)))))

(deftest parse-date-test
  (testing "parses EXIF formatted date string"
    (is (= (LocalDateTime/of 2023 10 5 14 30 15)
           (core/parse-date "2023:10:5 14:30:15")))))

(deftest read-metadata-test
  (testing "extracts and parses all fields from exiftool-shaped metadata"
    (let [metadata [{"DateTimeOriginal" "2023:10:5 14:30:15"
                      "ImageWidth" 1920
                      "ImageHeight" 1080
                      "GPSLatitude" "40 deg 26' 46.56\" N"
                      "GPSLongitude" "79 deg 59' 12.84\" W"}]]
      (is (= {:date (LocalDateTime/of 2023 10 5 14 30 15)
              :lat 40.446266666666665
              :lng -79.9869
              :imageWidth 1920
              :imageHeight 1080}
             (core/read-metadata metadata)))))

  (testing "missing GPS fields result in nil lat/lng"
    (let [metadata [{"DateTimeOriginal" "2023:10:5 14:30:15"
                      "ImageWidth" 640
                      "ImageHeight" 480}]]
      (is (= {:date (LocalDateTime/of 2023 10 5 14 30 15)
              :lat nil
              :lng nil
              :imageWidth 640
              :imageHeight 480}
             (core/read-metadata metadata))))))

(deftest get-images-test
  (testing "finds .jpg and .JPG files, ignoring other extensions"
    (fs/with-temp-dir [dir {}]
      (spit (str (fs/path dir "a.jpg")) "")
      (spit (str (fs/path dir "b.JPG")) "")
      (spit (str (fs/path dir "c.png")) "")
      (spit (str (fs/path dir "notes.txt")) "")
      (let [images (core/get-images (str dir))
            names (set (map fs/file-name images))]
        (is (= #{"a.jpg" "b.JPG"} names)))))

  (testing "empty directory returns no images"
    (fs/with-temp-dir [dir {}]
      (is (empty? (core/get-images (str dir)))))))

(deftest image-sizes-formats-test
  (testing "has one entry per size level"
    (is (= 6 (count core/image-sizes-formats))))

  (testing "full-size entry has no width and excludes jpg"
    (let [full (first (filter #(= "" (:suffix %)) core/image-sizes-formats))]
      (is (nil? (:width full)))
      (is (= [:avif :webp] (:formats full)))))

  (testing "non-full-size entries all include jpg/avif/webp with expected widths"
    (let [by-suffix (into {} (map (juxt :suffix identity) core/image-sizes-formats))]
      (doseq [[suffix expected-width] [["_l" 1024] ["_m" 500] ["_s" 240] ["_t" 150] ["_p" 10]]]
        (is (= expected-width (:width (get by-suffix suffix))))
        (is (= [:jpg :avif :webp] (:formats (get by-suffix suffix))))))))

(deftest image-format-size-variants-test
  (testing "produces one entry per magick invocation"
    (is (= 17 (count (core/image-format-size-variants)))))

  (testing "full-size jpg is never produced (handled separately via fs/copy)"
    (is (not-any? #(and (= "" (:suffix %)) (= :jpg (:format %)))
                  (core/image-format-size-variants))))

  (testing "every suffix/format pair is unique"
    (let [pairs (map (juxt :suffix :format) (core/image-format-size-variants))]
      (is (= (count pairs) (count (set pairs))))))

  (testing "contains expected variant for a resized jpg"
    (is (some #(= {:suffix "_l" :width 1024 :format :jpg} %)
              (core/image-format-size-variants)))))

(deftest image-target-dir-test
  (testing "builds the per-image output directory"
    (is (= (str (fs/path "base" "123")) (str (core/image-target-dir "base" "123"))))))

(deftest image-resize-command-test
  (testing "full-size variant omits the -resize flag"
    (let [cmd (core/image-resize-command "base" "123" "/src/123.jpg"
                                          {:suffix "" :format :avif :width nil})]
      (is (= ["magick" "/src/123.jpg" (str (fs/path "base" "123" "123.avif"))] cmd))))

  (testing "resized variant includes the -resize flag with a formatted width"
    (let [cmd (core/image-resize-command "base" "123" "/src/123.jpg"
                                          {:suffix "_l" :format :jpg :width 1024})]
      (is (= ["magick" "/src/123.jpg" "-resize" "1024x" (str (fs/path "base" "123" "123_l.jpg"))] cmd)))))

(deftest image-resize-commands-test
  (testing "produces exactly 17 magick commands with the expected output filenames"
    (let [commands (core/image-resize-commands "base" "123" "/src/123.jpg")
          out-paths (set (map last commands))
          expected-names #{"123.avif" "123.webp"
                            "123_l.jpg" "123_l.avif" "123_l.webp"
                            "123_m.jpg" "123_m.avif" "123_m.webp"
                            "123_s.jpg" "123_s.avif" "123_s.webp"
                            "123_t.jpg" "123_t.avif" "123_t.webp"
                            "123_p.jpg" "123_p.avif" "123_p.webp"}]
      (is (= 17 (count commands)))
      (is (= expected-names (set (map fs/file-name out-paths)))))))

(deftest coerce-imageid-test
  (testing "coerces a numeric string to a bigint"
    (is (= 123N (core/coerce-imageid "123"))))

  (testing "coerces a long to a bigint"
    (is (= 123N (core/coerce-imageid 123))))

  (testing "throws on a non-numeric string"
    (is (thrown? NumberFormatException (core/coerce-imageid "123.jpg"))))

  (testing "throws on nil"
    (is (thrown? NullPointerException (core/coerce-imageid nil)))))

(deftest save-image-parms-test
  (testing "maps metadata into the exact positional order expected by the INSERT statement"
    (let [date (LocalDateTime/of 2023 10 5 14 30 15)
          metadata {:date date :lat 40.4 :lng -79.9 :imageWidth 1920 :imageHeight 1080}]
      (is (= [1920 1080 "123" date 40.4 -79.9]
             (core/save-image-parms metadata "123")))))

  (testing "nil lat/lng pass through in their correct positions"
    (let [date (LocalDateTime/of 2023 10 5 14 30 15)
          metadata {:date date :lat nil :lng nil :imageWidth 640 :imageHeight 480}]
      (is (= [640 480 "123" date nil nil]
             (core/save-image-parms metadata "123"))))))

(deftest exif-output-path-test
  (testing "builds the exif sidecar json path"
    (is (= (str (fs/path "base" "123") "-exif.json")
           (core/exif-output-path "base" "123")))))

(deftest orig-metadata-path-test
  (testing "builds the original-filename metadata json path"
    (is (= (str (fs/path "base" "123") ".json")
           (core/orig-metadata-path "base" "123")))))
