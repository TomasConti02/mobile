# Yolo and MotionDetector

This directory contains the codebase for the YOLO inference model and the motion detector time-series logic.

Both class instances (`MotionDetectorFactory.kt` and `YoloProvider.kt`) are wrapped in factory classes to provide a cleaner initialization flow and ensure that only one active instance exists within the process.

`YoloProvider.kt` asynchronously provides access to the YOLO inference resources. This approach is required because loading model weights onto the mobile GPU hardware is a blocking operation, while the application runs on a single activity bound to the main UI thread.

## /yolo/MotionDetector.kt


## /yolo/YoloDetector.kt