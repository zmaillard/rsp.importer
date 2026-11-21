(ns rsp.postprocess-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [rsp.postprocess :as postprocess]))

(deftest new-name-test
  (testing "3-arity includes the edited path segment, size suffix, and type extension"
    (is (= "123/edited/123_t.avif" (postprocess/new-name "123" :thumbnail "avif")))
    (is (= "123/edited/123_p.webp" (postprocess/new-name "123" :placeholder "webp"))))
  (testing "2-arity defaults to .jpg"
    (is (= "123/edited/123_s.jpg" (postprocess/new-name "123" :small))))
  (testing "1-arity is key/edited/key.jpg"
    (is (= "123/edited/123.jpg" (postprocess/new-name "123")))))

(deftest new-name-no-scale-test
  (testing "2-arity uses the given type extension"
    (is (= "123/edited/123.avif" (postprocess/new-name-no-scale "123" "avif")))
    (is (= "123/edited/123.webp" (postprocess/new-name-no-scale "123" "webp"))))
  (testing "1-arity defaults to .jpg"
    (is (= "123/edited/123.jpg" (postprocess/new-name-no-scale "123")))))

(deftest extract-image-id-test
  (testing "extracts the first run of digits from a key"
    (is (= "12345" (postprocess/extract-image-id "ai/12345_edited.jpg"))))
  (testing "returns nil when there are no digits"
    (is (nil? (postprocess/extract-image-id "ai/no-digits-here.jpg")))))
