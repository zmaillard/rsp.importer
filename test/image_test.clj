(ns image-test
  (:require [clojure.test :refer [deftest testing is]]
            [babashka.fs :as fs]
            [babashka.tasks :as tasks]
            [clojure.data.json :as json]
            [pod.babashka.postgresql :as pg]
            [pod.zmaillard.snowflakeid :as snowflake]
            [image :as sut]
            [image.core :as core]))

;; NOTE: `image` requires the org.babashka/postgresql and
;; pod.zmaillard/snowflakeid pods at the namespace level, so both pods must
;; be resolvable (cached locally or downloadable) for this test namespace to
;; load at all. None of the tests below perform real DB, network, pod, or
;; external-binary (exiftool/rclone/magick) I/O though -- those calls are all
;; replaced via with-redefs. Only local, hermetic filesystem operations
;; (inside temp dirs) are allowed to run for real.

(defn- capture-calls
  "Returns [calls-atom stub-fn]. stub-fn records the args it's called with
  into calls-atom (as vectors) and returns `return-value`."
  ([] (capture-calls nil))
  ([return-value]
   (let [calls (atom [])]
     [calls (fn [& args] (swap! calls conj (vec args)) return-value)])))

(deftest exif-test
  (testing "shells out to exiftool for each image with the correct -json redirect path"
    (fs/with-temp-dir [dir {}]
      (spit (str (fs/path dir "a.jpg")) "")
      (let [[calls shell-stub] (capture-calls)]
        (with-redefs [tasks/shell shell-stub]
          (sut/exif {:path (str dir)}))
        (is (= 1 (count @calls)))
        (let [[opts tool flag file] (first @calls)]
          (is (= {:out (core/exif-output-path (str dir) "a")} opts))
          (is (= "exiftool" tool))
          (is (= "-json" flag))
          (is (= (str (fs/path dir "a.jpg")) (str file))))))))

(deftest generate-ids-test
  (testing "renames each image to <id>.jpg and writes <id>.json metadata containing the original filename"
    (fs/with-temp-dir [dir {}]
      (spit (str (fs/path dir "original-photo.jpg")) "fake-image-bytes")
      (with-redefs [snowflake/new-id (fn [_count] [1001])]
        (sut/generate-ids {:path (str dir)}))
      (is (fs/exists? (fs/path dir "1001.jpg")))
      (is (not (fs/exists? (fs/path dir "original-photo.jpg"))))
      (is (fs/exists? (fs/path dir "1001.json")))
      (is (= {"original" "original-photo.jpg"}
             (json/read-str (slurp (str (fs/path dir "1001.json")))))))))

(deftest import-new-test
  (testing "parses exif metadata, copies via rclone, saves to the DB, and deletes the staging original"
    (fs/with-temp-dir [dir {}]
      (let [exif-json [{"DateTimeOriginal" "2023:10:5 14:30:15"
                         "ImageWidth" 1920
                         "ImageHeight" 1080
                         "GPSLatitude" "40 deg 26' 46.56\" N"
                         "GPSLongitude" "79 deg 59' 12.84\" W"}]
            orig-json {"original" "original-photo.jpg"}]
        (spit (str (fs/path dir "123.jpg")) "fake-image-bytes")
        (spit (core/exif-output-path (str dir) "123") (json/write-str exif-json))
        (spit (core/orig-metadata-path (str dir) "123") (json/write-str orig-json))
        (let [[shell-calls shell-stub] (capture-calls)
              [pg-calls pg-stub] (capture-calls)]
          (with-redefs [tasks/shell shell-stub
                        pg/execute-one! pg-stub]
            (sut/import-new {:path (str dir)}))

          (testing "shells out to rclone copy then rclone deletefile, in order"
            (is (= 2 (count @shell-calls)))
            (is (= ["rclone copy" "--dry-run" "-vv"
                    (str (fs/absolutize (fs/path dir "123")))
                    "r2:/sign/123"]
                   (mapv str (first @shell-calls))))
            (is (= ["rclone deletefile" "--dry-run" "r2:sign/staging/original-photo.jpg"]
                   (mapv str (second @shell-calls)))))

          (testing "saves the parsed metadata to the DB with the correct positional params"
            (is (= 1 (count @pg-calls)))
            (let [[conn sql & params] (first @pg-calls)
                  expected-metadata (core/read-metadata exif-json)]
              (is (= sut/conn conn))
              (is (re-find #"INSERT INTO sign\.highwaysign_staging" sql))
              (is (= (core/save-image-parms expected-metadata "123") (vec params))))))))))

(deftest import-edited-test
  (testing "shells out to rclone copy/deletefile and marks the image processed in the DB"
    (fs/with-temp-dir [dir {}]
      (spit (str (fs/path dir "456.jpg")) "fake-image-bytes")
      (let [[shell-calls shell-stub] (capture-calls)
            [pg-calls pg-stub] (capture-calls)]
        (with-redefs [tasks/shell shell-stub
                      pg/execute-one! pg-stub]
          (sut/import-edited {:path (str dir)}))

        (testing "shells out to rclone copy then rclone deletefile, in order"
          (is (= 2 (count @shell-calls)))
          (is (= ["rclone copy" (str (fs/absolutize (fs/path dir "456"))) "r2:/sign/456/edited"]
                 (mapv str (first @shell-calls))))
          (is (= ["rclone deletefile" "r2:sign/ai/456.jpg"]
                 (mapv str (second @shell-calls)))))

        (testing "updates has_processed for the coerced imageid"
          (is (= 1 (count @pg-calls)))
          (let [[conn [sql imageid]] (first @pg-calls)]
            (is (= sut/conn conn))
            (is (re-find #"UPDATE sign\.highwaysign SET has_processed = true" sql))
            (is (= (core/coerce-imageid "456") imageid))))))))

(deftest resize-images-test
  (testing "creates the target dir, copies the full-size jpg, and shells out to magick for every variant"
    (fs/with-temp-dir [dir {}]
      (spit (str (fs/path dir "789.jpg")) "fake-image-bytes")
      (let [[shell-calls shell-stub] (capture-calls)]
        (with-redefs [tasks/shell shell-stub]
          (sut/resize-images {:path (str dir)}))

        (testing "creates the per-image target directory and copies the full-size jpg into it"
          (is (fs/exists? (fs/path dir "789")))
          (is (= "fake-image-bytes" (slurp (str (fs/path dir "789" "789.jpg"))))))

        (testing "shells out to magick for exactly the commands image.core would produce"
          (let [f (first (core/get-images (str dir)))]
            (is (= (set (core/image-resize-commands (str dir) "789" f))
                   (set @shell-calls))))))))

  (testing "does not fail when the target directory already exists"
    (fs/with-temp-dir [dir {}]
      (spit (str (fs/path dir "999.jpg")) "fake-image-bytes")
      (fs/create-dir (fs/path dir "999"))
      (let [[_ shell-stub] (capture-calls)]
        (with-redefs [tasks/shell shell-stub]
          (sut/resize-images {:path (str dir)}))
        (is (fs/exists? (fs/path dir "999" "999.jpg")))))))
