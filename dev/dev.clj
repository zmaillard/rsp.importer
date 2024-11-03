(ns dev
  (:require [babashka.fs :as fs]
            [rsp.image :as image])
  (:import (org.imgscalr Scalr Scalr$Method Scalr$Mode)))

(defn process-local
  [dir-name ^String sign size]
  (let [image (ImageIO/read (File. sign))
        new-width (get-image-width size)
        image-id (fs/strip-ext (fs/file-name sign))
        resized (Scalr/resize image Scalr$Method/ULTRA_QUALITY Scalr$Mode/FIT_TO_WIDTH new-width 0 image/antialias-op)
        writer (image/get-image-writers)
        output-dir (fs/path dir-name image-id)
        output-name (fs/path output-dir (str image-id "_" (get-image-name size) ".jpg"))]
    (println (exif-for-filename sign))
    ;(fs/create-dirs output-dir)
    ;(.setOutput writer (FileImageOutputStream. (File. (str output-name))))
    ;(.write writer nil (IIOImage. resized nil nil) (image/get-jpeg-quality))
    (.dispose writer)))

(defn run-local
  [dir-name]
  (let [signs (fs/glob dir-name "*.JPG")]
    (doseq [sign signs]
      (doseq [size (keys image-size)]
        (process-local dir-name (str sign) size)))))

(defn test
  []
  (let [metadata (exif-for-filename "/Users/zachm/Downloads/imports/IMG_2582.JPG")]
    (parse-lat-lng metadata)))
;-115° 45' 12.5"
;42° 55' 57.98"

;{Compression Type Baseline, FlashPix Version 1.00, Shutter Speed Value 1/2747 sec, Component 2 Cb component: Quantization table 1, Sampling factors 1 horiz/1 vert, Software 18.1, Color Space Undefined, Orientation Top, left side (Horizontal / normal), Focal Length 35 26 mm, F-Number f/1.6, GPS Longitude -115° 57' 16.58", GPS Latitude Ref N, GPS Speed 1.71 km/h, Data Precision 8 bits, Aperture Value f/1.6, White Balance Mode Auto white balance, Date/Time Digitized 2024:11:02 12:00:33, Sensing Method One-chip color area sensor, GPS Date Stamp 2024:11:02, Make Apple, Component 1 Y component: Quantization table 0, Sampling factors 2 horiz/2 vert, Scene Type Directly photographed image, Scene Capture Type Standard, Time Zone Original -06:00, Subject Location 2012 1510 2217 1393, GPS Horizontal Positioning Error 5.31 metres, ISO Speed Ratings 32, Exif Version 2.32, GPS Altitude 1011.73 metres, Metering Mode Multi-segment, GPS Longitude Ref W, Exposure Mode Auto exposure, Thumbnail Offset 2900 bytes, Host Computer iPhone 12 mini, Image Width 4032 pixels, Y Resolution 72 dots per inch, Components Configuration YCbCr, YCbCr Positioning Center of pixel array, Lens Specification 1625285/1048571-4.2mm f/1.6-2.4, Lens Model iPhone 12 mini back dual wide camera 4.2mm f/1.6, GPS Altitude Ref Sea level, GPS Img Direction Ref True direction, Flash Flash did not fire, Date/Time 2024:11:02 12:00:33, Model iPhone 12 mini, Lens Make Apple, Component 3 Cr component: Quantization table 1, Sampling factors 1 horiz/1 vert, Resolution Unit Inch, Sub-Sec Time Original 389, GPS Speed Ref km/h, GPS Latitude 43° 20' 36.06", Image Height 3024 pixels, Brightness Value 9.597, Sub-Sec Time Digitized 389, Date/Time Original 2024:11:02 12:00:33, X Resolution 72 dots per inch, Focal Length 4.2 mm, GPS Img Direction 260.29 degrees, Unknown tag (0xa460) 2, Thumbnail Length 5919 bytes, Exposure Time 1/2747 sec, Compression JPEG (old-style), Number of Components 3, Exposure Program Program normal, Time Zone -06:00, Exposure Bias Value 0 EV, Exif Image Width 4032 pixels, Time Zone Digitized -06:00, GPS Dest Bearing 260.29 degrees, GPS Time-Stamp 18:00:31.000 UTC, GPS Dest Bearing Ref True direction, Exif Image Height 3024 pixels}
