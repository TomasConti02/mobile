# Camera Access App

A costume Android application demonstrating integration between Meta Wearables Device Access Toolkit and ComputerVision mobile models. 
This app stream video frames coming from Meta AI glasses (or mock device), capturing photos, and managing connection states. 
There is also a computervision pipe line attach to the video stream to define the state of the scene and tigger a enbedded model.
A Pre-train yolo model execute the inference for object detection in stable scenes.
The application is based on [ meta-wearables-dat-android ](https://github.com/facebook/meta-wearables-dat-android) open source code base.

## Features

- Connect to Meta AI glasses
- Stream camera feed from the device
- Capture photos from glasses
- Share captured photos

Something new:

- Streaming scene stabilization aware [ plug in ]
- YOLO obejct detection [ plug in ]

 
 
## Application Architecture

The code based is the same as meta-wearables-dat-android source code project with some plug in.
Into dir /assets there are models and metdata informations.
Into directory /yolo there are Streaming scene stabilization and yolo inference code.
In order to implement the pulgin some changes into the source code has been done:
- MianActivity
- /stream/StreamViewModel
- /ui/StreamScreen

All plug in are explain into the README.md into the /yolo dir
## MianActivity
## /stream/StreamViewModel
## /ui/StreamScreen
