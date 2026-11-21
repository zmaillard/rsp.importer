(ns rsp.cloud-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [cognitect.aws.client.api :as aws]
   [rsp.cloud :as cloud]
   [rsp.image :as image])
  (:import
   (java.awt.image BufferedImage)))

;; --- Tier 1: pure logic -----------------------------------------------

(deftest get-mime-test
  (is (= "image/jpeg" (cloud/get-mime "jpg")))
  (is (= "image/webp" (cloud/get-mime "webp")))
  (is (= "image/avif" (cloud/get-mime "avif")))
  (testing "unsupported type throws"
    (let [ex (try (cloud/get-mime "png")
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= {:type "png"} (ex-data ex))))))

;; --- Tier 3: mocked S3 wiring -------------------------------------------

(defn- capture-invokes
  "Returns [calls fake-invoke-fn]. fake-invoke-fn records every request
  map it is called with into the calls atom, and returns a canned
  response so callers that destructure the result don't blow up."
  []
  (let [calls (atom [])]
    [calls
     (fn fake-invoke [_client request]
       (swap! calls conj request)
       {})]))

(defn- with-fake-aws
  "Runs f with aws/invoke and cloud/s3-client replaced by fakes, and
  returns the atom of captured requests."
  [f]
  (let [[calls fake-invoke] (capture-invokes)]
    (with-redefs [aws/invoke fake-invoke
                  cloud/s3-client (fn [] :fake-client)
                  ;; Avoid depending on native codecs / real encoding for
                  ;; wiring assertions - just check the plumbing.
                  image/encode-image (fn [_image _type] (byte-array [1 2 3]))]
      (f))
    calls))

(deftest scale-image-sends-expected-request-test
  (doseq [type ["jpg" "webp" "avif"]]
    (testing type
      (let [calls (with-fake-aws
                    #(cloud/scale-image (str "123/123_t." type)
                                         (BufferedImage. 16 16 BufferedImage/TYPE_INT_RGB)
                                         :thumbnail
                                         type))
            {:keys [op request]} (first @calls)]
        (is (= :PutObject op))
        (is (= "sign" (:Bucket request)))
        (is (= (str "123/123_t." type) (:Key request)))
        (is (= (cloud/get-mime type) (:ContentType request)))
        (is (pos? (count (:Body request))))))))

(deftest convert-image-sends-expected-request-test
  (doseq [type ["jpg" "webp" "avif"]]
    (testing type
      (let [calls (with-fake-aws
                    #(cloud/convert-image (str "123/123." type)
                                           (BufferedImage. 16 16 BufferedImage/TYPE_INT_RGB)
                                           type))
            {:keys [op request]} (first @calls)]
        (is (= :PutObject op))
        (is (= "sign" (:Bucket request)))
        (is (= (str "123/123." type) (:Key request)))
        (is (= (cloud/get-mime type) (:ContentType request)))
        (is (pos? (count (:Body request))))))))

(deftest delete-image-sends-expected-request-test
  (let [calls (with-fake-aws #(cloud/delete-image "123/123.jpg"))
        {:keys [op request]} (first @calls)]
    (is (= :DeleteObject op))
    (is (= "sign" (:Bucket request)))
    (is (= "123/123.jpg" (:Key request)))))

(deftest copy-image-sends-expected-request-test
  (let [calls (with-fake-aws #(cloud/copy-image "old-key.jpg" "123/123.jpg"))
        {:keys [op request]} (first @calls)]
    (is (= :CopyObject op))
    (is (= "sign" (:Bucket request)))
    (is (= "123/123.jpg" (:Key request)))
    (is (= "/sign/old-key.jpg" (:CopySource request)))))
