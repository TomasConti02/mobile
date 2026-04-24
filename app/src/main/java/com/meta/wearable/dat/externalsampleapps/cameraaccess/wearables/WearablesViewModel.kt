/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// WearablesViewModel - Core DAT SDK Integration
//
// This ViewModel demonstrates the core DAT API patterns for:
// - Device registration and unregistration using the DAT SDK
// - Permission management for wearable devices
// - Device discovery and state management
// - Integration with MockDeviceKit for testing

package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables
import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import com.meta.wearable.dat.core.Wearables //sdk
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector //sdk
import com.meta.wearable.dat.core.selectors.DeviceSelector //sdk track the active Wearables.devices
import com.meta.wearable.dat.core.types.DeviceIdentifier //sdk
import com.meta.wearable.dat.core.types.Permission //sdk
import com.meta.wearable.dat.core.types.PermissionStatus //sdk
import com.meta.wearable.dat.core.types.RegistrationState //sdk
//manage the registration and device access, traffic data flow, security ecc
import com.meta.wearable.dat.mockdevice.MockDeviceKit //sdk import of the sdk of the application, the entry point of the sdk for the app

import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
/* * 1. viewModelScope.launch: Opens a coroutine bound to the ViewModel's lifecycle.
 * If the user closes the screen, the coroutine is cancelled automatically (No "zombie" processes).
 * This saves battery and prevents memory leaks.
 * * 2. Main Thread Safety: Android handles UI (clicks/animations) on the Main Thread.
 * Monitoring hardware is a heavy task; by using a coroutine, we avoid blocking the
 * Main Thread, preventing the app from freezing (ANR).
 * * 3. Wearables.devices: The raw data stream (Kotlin Flow) observing all detected Meta devices.
 * * 4. deviceSelector.activeDevice: A filter that identifies the specific device
 * currently selected or active for the session.
 * * 5. .collect { device -> ... }: A reactive listener that stays active for the
 * duration of the coroutine. It triggers every time there is a hardware update
 * (connect/disconnect), passing the new 'device' value to update the UI State.
 * launch output is a job object pointer, alter the use of it we need to clear the memory
 * but also, cloud be only one job listening the device channel
 */
class WearablesViewModel(application: Application) : AndroidViewModel(application) {
  companion object {  private const val TAG = "WearablesViewModel"  }
  //Pattern Unidirectional Data Flow (UDF), modern android (Jetpack Compose + ViewModel)
  //WearablesUiState() -> class with all the information about Wearables
  private val _uiState = MutableStateFlow(WearablesUiState()) // _uiState is a MutableStateFlow, private for this class that can R/W
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow() // uiState is the read only view from outside
  // AutoDeviceSelector automatically selects the first available wearable device
  val deviceSelector: DeviceSelector = AutoDeviceSelector() //part of the meta wearable sdk class
  private var deviceSelectorJob: Job? = null
  private var monitoringStarted = false
  private val deviceMonitoringJobs = mutableMapOf<DeviceIdentifier, Job>()

  private fun startMonitoring() { //start the monitoring of the devices sdk/ start the Wearable monitoring
    if (monitoringStarted) {  return  }
    monitoringStarted = true
    deviceSelectorJob = viewModelScope.launch {  deviceSelector.activeDevice(Wearables.devices).collect { device ->
          // don't rebuild all the ui state, just copy it and change the device setting part
            _uiState.update { it.copy(hasActiveDevice = device != null) } //update the ui state based on the device presence
          }
        }
    //Observe registration and device updates.
    viewModelScope.launch { Wearables.registrationState.collect { value -> //launch a job/corutine listening the device registration state channel for all the update
        val previousState = _uiState.value.registrationState //past state taken fron the ui state
        val showGettingStartedSheet = value is RegistrationState.Registered && previousState is RegistrationState.Registering
        //showGettingStartedSheet became true only if the state pass from previousState(registering) to value(registered)
        //passiamo lo stato nuovo se siamo passati dallo stato in registrazione a quello registrato easy!!!
        _uiState.update {  it.copy(registrationState = value, isGettingStartedSheetVisible = showGettingStartedSheet) }
      }
    }

    viewModelScope.launch { Wearables.devices.collect { value ->
      //check id there are mock software device created by the developer and pass this state to the interface
      //if a new mock device item has been added into the MockDevicekitScreen so start the NonStreamScreen as in case of registration a real pair
        val hasMockDevices = MockDeviceKit.getInstance(getApplication()).pairedDevices.isNotEmpty() //IF THE DEVICE IS A MOCK ONE
        _uiState.update { it.copy(devices = value.toList().toImmutableList(), hasMockDevices = hasMockDevices) }
        // Monitor device metadata for compatibility issues
        monitorDeviceCompatibility(value)
      }
      //update to the ui the new devices, if there are some mock device update this event
    }
  }

  private fun monitorDeviceCompatibility(devices: Set<DeviceIdentifier>) {
    // Cancel monitoring jobs for devices that are no longer in the list
    val removedDevices = deviceMonitoringJobs.keys - devices
    removedDevices.forEach { deviceId ->
      deviceMonitoringJobs[deviceId]?.cancel()
      deviceMonitoringJobs.remove(deviceId)
    }

    // Start monitoring jobs only for new devices (not already being monitored)
    val newDevices = devices - deviceMonitoringJobs.keys
    newDevices.forEach { deviceId ->
      val job =  viewModelScope.launch {  Wearables.devicesMetadata[deviceId]?.collect { metadata ->
              if (
                  metadata.compatibility ==
                      com.meta.wearable.dat.core.types.DeviceCompatibility.DEVICE_UPDATE_REQUIRED
              ) {
                val deviceName = metadata.name.ifEmpty { deviceId }
                setRecentError("Device '$deviceName' requires an update to work with this app")
              }
            }
          }
      deviceMonitoringJobs[deviceId] = job
    }
  }
  //Register your application with the Meta AI app either at startup or when the user wants to turn on your wearables integration.
  fun startRegistration(activity: Activity) { Wearables.startRegistration(activity)  } //start the registration by sdk
  fun startUnregistration(activity: Activity) { Wearables.startUnregistration(activity) } //end the registrationm

//before the stream session
  fun navigateToStreaming(onRequestWearablesPermission: suspend (Permission) -> PermissionStatus) {
    viewModelScope.launch { //async operation
      val permission = Permission.CAMERA // Camera permission is required for streaming
      val result = Wearables.checkPermissionStatus(permission) //is the user already registered ?
      // Handle the result
      result.onFailure { error, _ ->
        setRecentError("Permission check error: ${error.description}")
        return@launch
      }
      val permissionStatus = result.getOrNull()
      if (permissionStatus == PermissionStatus.Granted) { //ok change ui statu for registering
        _uiState.update { it.copy(isStreaming = true) }
        return@launch
      }
      // not yet registered ->  Request permission need by onRequestWearablesPermission(permission) function
      val requestedPermissionStatus = onRequestWearablesPermission(permission)
      when (requestedPermissionStatus) {
        PermissionStatus.Denied -> {
          setRecentError("Permission denied")
        }
        PermissionStatus.Granted -> {
          _uiState.update { it.copy(isStreaming = true) }
        }
      }
    }
  }

  fun navigateToDeviceSelection() {
    _uiState.update { it.copy(isStreaming = false) }
  }

  fun showDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = true) }
  }

  fun hideDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = false) }
  }

  fun clearRecentError() {
    _uiState.update { it.copy(recentError = null) }
  }

  private fun setRecentError(error: String) {
    _uiState.update { it.copy(recentError = error) }
  }

  fun onPermissionsResult(permissionsResult: Map<String, Boolean>, onAllGranted: () -> Unit) {
    val granted = permissionsResult.entries.all { it.value } //if all there are all basic permission
    _uiState.update { it.copy(canRegister = granted) }
    if (granted) {
      onAllGranted() //execute Wearables.initialize(this)
      startMonitoring()
    } else {
      _uiState.update { it.copy(recentError = "Allow All Permissions (Bluetooth, Bluetooth Connect, Internet)") }
    }
  }

  fun showGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = true) }
  }

  fun hideGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = false) }
  }

  override fun onCleared() {
    super.onCleared()
    // Cancel all device monitoring jobs when ViewModel is cleared
    deviceMonitoringJobs.values.forEach { it.cancel() }
    deviceMonitoringJobs.clear()
    deviceSelectorJob?.cancel() //closing
  }
}
