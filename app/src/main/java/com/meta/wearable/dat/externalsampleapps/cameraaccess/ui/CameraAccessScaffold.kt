/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraAccessScaffold - DAT Application Navigation Orchestrator
//
// This scaffold demonstrates a typical DAT application navigation pattern based on device
// registration and streaming states from the DAT API.
//
// DAT State-Based Navigation:
// - HomeScreen: When NOT registered (uiState.isRegistered = false) Shows initial registration UI
//   calling Wearables.startRegistration()
// - NonStreamScreen: When registered (uiState.isRegistered = true) but not streaming Shows device
//   selection, permission checking, and pre-streaming setup
// - StreamScreen: When actively streaming (uiState.isStreaming = true) Shows live video from
//   StreamSession.videoStream and photo capture UI
//
// The scaffold also provides a debug menu (in DEBUG builds) that gives access to
// MockDeviceKitScreen for testing DAT functionality without physical devices.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.meta.wearable.dat.core.types.Permission //sdk
import com.meta.wearable.dat.core.types.PermissionStatus //sdk

import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

//scaffold call CameraAccessScaffold it is the principal UI component, manage the navigation
//based on the wearable device state show up different ui
@OptIn(ExperimentalMaterial3Api::class) //  Interfaccia=f(Stato)
@Composable //  the principal component for manage the view, execute as a router, based con state change the view
fun CameraAccessScaffold( viewModel: WearablesViewModel, onRequestWearablesPermission: suspend (Permission) -> PermissionStatus, modifier: Modifier = Modifier, ) {
    //track the uistate of the device in real time to orchestrete the application
  val uiState by viewModel.uiState.collectAsStateWithLifecycle() //get the read only camera uistate of weareable
  val snackbarHostState = remember { SnackbarHostState() }
  val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    //  WearablesViewModel keep the state of the system
  // Observe recent errors and show snackbar
  LaunchedEffect(uiState.recentError) {
    uiState.recentError?.let { errorMessage ->
      snackbarHostState.showSnackbar(errorMessage)
      viewModel.clearRecentError()
    }
  }
    //
  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize()) {
      when { //here start the logic based on state
          //the streaming is started and so start StreamScreen, passing the wearable camera device class wearablesViewModel
        uiState.isStreaming -> StreamScreen( wearablesViewModel = viewModel,) //if the state is in streaming show up the stream screen
          //chose the waerable device, after the pair operation the uistate is registered and NonStreamScreen show up
        uiState.isRegistered -> NonStreamScreen( viewModel = viewModel, onRequestWearablesPermission = onRequestWearablesPermission,)
        else ->
            HomeScreen(viewModel = viewModel,) // if there is no device registered and no stream showup the home
      }
      SnackbarHost( //manage the error example lost of internet of bluetooth ..
          hostState = snackbarHostState,
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .padding(horizontal = 16.dp, vertical = 32.dp),
          snackbar = { data ->
            Snackbar(
                shape = RoundedCornerShape(24.dp),
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Camera Access error",
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(data.visuals.message)
              }
            }
          },
      )
        //Mock devive is on???
      if (BuildConfig.DEBUG) { //if we are in to the debug mode (according to the conf), show up the debug item
          //start application view model
        FloatingActionButton( onClick = {
            viewModel.showDebugMenu() }, //allow the model to set the ui state model right
            modifier = Modifier.align(Alignment.CenterEnd),) {  Icon(Icons.Default.BugReport, contentDescription = "Debug Menu")  }
        if (uiState.isDebugMenuVisible) { //the click change the viewmodel state and open MockDeviceKitScreen
          ModalBottomSheet( onDismissRequest = { viewModel.hideDebugMenu() },
              sheetState = bottomSheetState,
              //MockDeviceKitScreen manage MockDeviceKitViewModel and no more the classic view model WearablesViewModel
              modifier = Modifier.fillMaxSize(),) {  MockDeviceKitScreen(modifier = Modifier.fillMaxSize())  }
        }//end if
      }
    }
  }
}
