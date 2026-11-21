(ns rsp.staging-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [rsp.staging :as staging])
  (:import
   (java.awt.image BufferedImage)))

(deftest new-name-test
  (testing "3-arity includes the size suffix and the given type extension"
    (is (= "123/123_t.avif" (staging/new-name "123" :thumbnail "avif")))
    (is (= "123/123_p.webp" (staging/new-name "123" :placeholder "webp")))
    (is (= "123/123_l.jpg" (staging/new-name "123" :large "jpg"))))
  (testing "2-arity defaults to .jpg"
    (is (= "123/123_s.jpg" (staging/new-name "123" :small))))
  (testing "1-arity is just key/key.jpg"
    (is (= "123/123.jpg" (staging/new-name "123")))))

(deftest new-name-no-scale-test
  (testing "2-arity uses the given type extension"
    (is (= "123/123.avif" (staging/new-name-no-scale "123" "avif")))
    (is (= "123/123.webp" (staging/new-name-no-scale "123" "webp"))))
  (testing "1-arity defaults to .jpg"
    (is (= "123/123.jpg" (staging/new-name-no-scale "123")))))

(deftest get-dimensions-test
  (let [image (BufferedImage. 64 32 BufferedImage/TYPE_INT_RGB)]
    (is (= {:width 64 :height 32} (staging/get-dimensions image)))))

(deftest build-decimal-degrees-test
  (testing "positive coordinate"
    (is (= 40.0 (staging/build-decimal-degrees "40° 0' 0\""))))
  (testing "negative coordinate"
    (is (= -73.5 (staging/build-decimal-degrees "-73° 30' 0\"")))))

(deftest parse-lat-long-test
  (testing "both present"
    (is (= {:latitude 40.0 :longitude -73.5}
           (staging/parse-lat-long "40° 0' 0\"" "-73° 30' 0\""))))
  (testing "missing latitude"
    (is (= {:latitude nil :longitude nil}
           (staging/parse-lat-long nil "-73° 30' 0\""))))
  (testing "missing longitude"
    (is (= {:latitude nil :longitude nil}
           (staging/parse-lat-long "40° 0' 0\"" nil)))))
