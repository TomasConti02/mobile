# Yolo and MotionDetector

This directory contains the codebase for the YOLO inference model and the motion detector time-series logic.

Both class instances (`MotionDetectorFactory.kt` and `YoloProvider.kt`) are wrapped in factory classes to provide a cleaner initialization flow and ensure that only one active instance exists within the process.

`YoloProvider.kt` asynchronously provides access to the YOLO inference resources. This approach is required because loading model weights onto the mobile GPU hardware is a blocking operation, while the application runs on a single activity bound to the main UI thread.

## /yolo/MotionDetector.kt

Based on the OpenCV SDK.

Bitmaps received from the frame channel in `StreamViewModel.kt` are converted to grayscale and reduced in scale to decrease the number of pixels to analyze and reduce CPU usage.

The pipeline computes the pixel-wise matrix difference with the previous frame, then applies a blur and denoise kernel operation. A lightweight blur kernel is used because it is less computationally expensive than a Gaussian kernel. A threshold is then applied to filter out outliers and small variations.

After this step, the pipeline processes blob shapes and performs morphological opening operations to remove noise and clean the detected regions.

Finally, the algorithm computes the amount of motion detected between frames. The motion state is stored as a time series and updated according to both the current frame analysis and the previous state history.

### THIS OPERATION IS CPU-INTENSIVE AND COULD BE AVOIDED BY USING THE WEARABLE SDK APIS TO GATHER MOTION DATA DIRECTLY FROM THE DEVICE SENSORS.



## /yolo/YoloDetector.kt

Based on the [TensorFlow Lite SDK](https://blog.tensorflow.org/2018/03/using-tensorflow-lite-on-android.html).

The detector uses a [YOLOv8](https://docs.ultralytics.com/it/models/yolov8) model pretrained on the COCO dataset. Two different model variants are used:

* an 8-bit quantized model
* a 32-bit floating-point model

The 32-bit model is executed on the mobile GPU when compatible hardware acceleration is available. Otherwise, the application falls back to the smaller 8-bit quantized model, executed on the CPU using 4 threads.

This design represents a trade-off between:

* inference speed
* model accuracy
* resource utilization

Quantization reduces memory usage and improves execution speed even on cpu architecture, while the 32-bit model provides higher detection precision and accuracy. 

Useful to convert the model in a quantization 8bit format:
```bash
yolo export model=yolov8n.pt format=tflite int8
```
Re-train-Tuning of the model if needed

[Google colab source code](https://colab.research.google.com/github/roboflow-ai/notebooks/blob/main/notebooks/train-yolov8-object-detection-on-custom-dataset.ipynb#scrollTo=jbVjEtPAkz3j)