/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraAccess Sample App - Main Activity
//
// This is the main entry point for the CameraAccess sample application that demonstrates how to use
// the Meta Wearables Device Access Toolkit (DAT) to:
// - Initialize the DAT SDK
// - Handle device permissions (Bluetooth, Internet)
// - Request camera permissions from wearable devices (Ray-Ban Meta glasses)
// - Stream video and capture photos from connected wearable devices
package com.meta.wearable.dat.externalsampleapps.cameraaccess
import android.Manifest.permission.BLUETOOTH
import android.Manifest.permission.BLUETOOTH_CONNECT
import android.Manifest.permission.INTERNET
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity //android activity for user screen interaction changes
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.YoloProvider
import com.meta.wearable.dat.core.Wearables //sdk
import com.meta.wearable.dat.core.types.Permission //sdk
import com.meta.wearable.dat.core.types.PermissionStatus //sdk
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.YoloDetector

import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CameraAccessScaffold
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.lifecycleScope
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import kotlinx.coroutines.launch
// https://wearables.developer.meta.com/docs/reference/android/dat/0.4/com_meta_wearable_dat_core_wearables
// https://wearables.developer.meta.com/docs/reference/android/dat/0.4
class MainActivity : ComponentActivity() { //one activity, the main

  companion object {
    val PERMISSIONS: Array<String> = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, INTERNET)//basic permissions
  }
    private val TAG = "MAIN"
  val viewModel: WearablesViewModel by viewModels() //declare the WearablesViewModel with -> by -> jetpack-property delegation
  private val permissionCheckLauncher = registerForActivityResult(RequestMultiplePermissions()) { permissionsResult -> //check about the basic permissions
        viewModel.onPermissionsResult(permissionsResult) {  //viewModel exec a double permission check
          Wearables.initialize(this) // Initialize the SDK
        }
      }
  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val permissionMutex = Mutex()
    // Requesting wearable device permissions via the Meta AI app
  private val permissionsResultLauncher = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val permissionStatus = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(permissionStatus)
        permissionContinuation = null
      }
  // Convenience method to make a permission request in a sequential manner
// Uses a Mutex to ensure requests are processed one at a time, preventing race conditions
  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
    return permissionMutex.withLock { //atomicity execution, serializable and not thread concurrency, only one process at time execute requestWearablesPermission
        suspendCancellableCoroutine { continuation -> permissionContinuation = continuation //saving the corrent state, saving the corouting reference
        continuation.invokeOnCancellation { permissionContinuation = null } //if the coruting wait is interrupt not panic and empty the permissionContinuation
        permissionsResultLauncher.launch(permission) //open the meta AI app interface over my screen
      }
    }
  }

//executed on application creation
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState) //set the standard android conf
    enableEdgeToEdge()//view option, cover all the screen
    setContent {  CameraAccessScaffold(viewModel = viewModel, onRequestWearablesPermission = ::requestWearablesPermission,) } //set he UI Scaffold passing the WearablesViewModel
    lifecycleScope.launch { //corutine for singleton asincrono lazy, yolo model loading
        YoloProvider.getAsync(applicationContext).await()
    }
    Log.d(TAG, "main thread is started with all the resources")
  }
    override fun onDestroy() {
        super.onDestroy()
        YoloProvider.close() // deallocate the resources
        Log.d(TAG, "main thread closed with all the resources")
    }
  //executed on application start
  override fun onStart() { //on start check internet and bluetooth permissions
    super.onStart()
    permissionCheckLauncher.launch(PERMISSIONS)//at every start check the basic permission if they are still available
  }
}
