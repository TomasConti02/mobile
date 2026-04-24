# Camera Access App
A costume sample Android application demonstrating integration with Meta Wearables Device Access Toolkit. This app showcases streaming video from Meta AI glasses, capturing photos, and managing connection states. New Computer Vision YOLO model for items filtering embedded into the Meta Wearables Device data stream.
## Features
- Connect to Meta AI glasses
- Stream camera feed from the device
- Capture photos from glasses
- Share captured photos
- Streaming scene stabilization aware
- YOLO obejct detection
## Prerequisites
- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Wearables Device Access Toolkit (included as a dependency)
- A Meta AI glasses device for testing (optional for development)
- OpenCV framework
- Yolo 8bit quant already included into the /assets dir
 
 
## 🏗️ Application Architecture

The project follows a **Reactive State-Driven** architecture. The UI does not handle logic; instead, it observes a centralized state that reacts to hardware events from the Meta Wearables SDK.

### 📑 Key Components

#### **1. MainActivity**
The entry point of the application.
* **System Requirements:** Declares and checks for mandatory requirements such as **Internet** and **Bluetooth**.
* **Orchestration:** Initializes the `CameraAccessScaffold` and injects the `WearablesViewModel`.
* **Lifecycle & Permissions:** Manages the initial permission flow. Once permissions are granted, it triggers the initialization of the Wearables SDK components.

---

#### **2. Wearables Module (`/wearables`)**
This directory contains the core logic for hardware interaction.

* **`WearablesUIState`**: A data class representing the "Single Source of Truth." It holds the current status of the glasses, including:
    * Connection and Registration status (`isRegistered`).
    * Active streaming state (`isStreaming`).
    * List of discovered devices and metadata compatibility.
* **`WearablesViewModel`**: The bridge between the SDK and the UI.
    * **Monitoring Loops:** Uses Kotlin Coroutines (`viewModelScope`) and Flows to listen for device updates in parallel.
    * **State Management:** Automatically updates `WearablesUIState` whenever a device is discovered, connected, or disconnected.
---


#### **3. UI Module (`/ui`)**
This module manages the visual representation of the application state.

* **`CameraAccessScaffold`**: The primary UI Orchestrator. 
    * **State Observation:** Utilizes `collectAsStateWithLifecycle` to observe the ViewModel in a lifecycle-aware manner.
    * **State-Based Navigation:** Instead of traditional routing, it uses a logic-based `when` block to toggle between:
        * `StreamScreen`: Active when the device is streaming video.
        * `NonStreamScreen`: Active when the device is registered but idle (setup/permissions).
        * `HomeScreen`: Active for new users or initial registration.
    * **Debug Tools:** In DEBUG builds, it provides a `ModalBottomSheet` that acts as a container for the hardware simulation interface.
---

#### **4. Mock Device Module (`/mockdevicekit`)**
Provides the logic for simulating hardware without physical devices.

* **`MockDeviceKitUiState`**: A dedicated state class for simulated devices. Similar to `WearablesUIState`, it tracks power status, wear detection (Don/Doff), and assigned media for virtual devices.
* **`MockDeviceKitViewModel`**: The logic provider for the simulation.
    * **Operations:** Implements hardware-emulation commands (e.g., Power On/Off, Fold/Unfold, Don/Doff).
    * **SDK Interaction:** Interfaces with the Meta Wearables SDK's mock provider to ensure virtual devices behave like physical ones.
    

---

#### **4. Mock Device Module (`/mockdevicekit`)**
This module provides the logic for simulating hardware without physical devices.

* **`MockDeviceKitViewModel`**: The logic provider for the simulation.
    * **Operations:** Implements all hardware-emulation commands called by the `MockDeviceKitScreen` (e.g., Power On/Off, Fold/Unfold, Don/Doff).
    * **SDK Interaction:** It interfaces with the Meta Wearables SDK's mock provider to ensure the app treats virtual devices exactly like real physical glasses.
 
 
 
 
## Running the app

1. Turn 'Developer Mode' on in the Meta AI app.
1. Launch the app.
1. Press the "Connect" button to complete app registration.
1. Once connected, the camera stream from the device will be displayed
1. Use the on-screen controls to:
   - Capture photos
   - View and save captured photos
   - Disconnect from the device

## Troubleshooting

For issues related to the Meta Wearables Device Access Toolkit, please refer to the [developer documentation](https://wearables.developer.meta.com/docs/develop/) or visit our [discussions forum](https://github.com/facebook/meta-wearables-dat-android/discussions)

## License

This source code is licensed under the license found in the LICENSE file in the root directory of this source tree.
# mobile
