/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.
/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text   // <-- IMPORT per Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color   // <-- IMPORT per Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight   // <-- IMPORT per FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp   // <-- IMPORT per sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.example.motion.MotionDetector   // <-- IMPORT per la tua classe MotionDetector
//@compose function and not a class, used to show up the wearable video stream
//it is a view UI part of Jetpack Compose
// used into Activity or navigation graph
//To the stream screen as allways the WearablesViewModel but also streamViewModel for the stream coordination
@Composable
fun StreamScreen( wearablesViewModel: WearablesViewModel,  modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel = viewModel(factory = StreamViewModel.Factory(application = (LocalActivity.current as ComponentActivity).application, wearablesViewModel = wearablesViewModel,),), )
    {
    //streamViewModel -> manage the camera frame frm the hardware and send to the view StreamScreen for showing up
    // observed states
    // for each change update UI view
    // Flow + lifecycle-aware collection is used
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()

    val motionState by streamViewModel.motionState.collectAsStateWithLifecycle() // ADDED
    val detectedObject by streamViewModel.detectedObject.collectAsStateWithLifecycle() //ADDED
    //launch a Coroutine executing in parallel with the model for the data stream
    LaunchedEffect(Unit) { streamViewModel.startStream() } //FIRST IN FIRST START THE STREAM BY THE MODEL OF THE STREAM
    Box(modifier = modifier.fillMaxSize()) {
        //when the model receive a new frame update the ui and this trigger the stream screen state
        streamUiState.videoFrame?.let { videoFrame -> //receive all the video fream for the ui streamUiState
            key(streamUiState.videoFrameCount) { //used for stream refresh when the frame change
                Image(
                    bitmap = videoFrame.asImageBitmap(), //convert the bitmap frame into ImageBitmap
                    contentDescription = stringResource(R.string.live_stream),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // Overlay del testo (posizionato sopra l'immagine)
            Text(
                text = when (motionState) {
                    MotionDetector.State.MOVING -> "🔴 MOVING"
                    MotionDetector.State.STILL  -> "🟡 STILL"
                    MotionDetector.State.STABLE -> "🟢 STABLE"
                },
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            if (detectedObject != null) {
                Text(
                    text = "📷 $detectedObject",
                    color = Color.Yellow,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 56.dp, start = 16.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SwitchButton(
                    label = stringResource(R.string.stop_stream_button_title),
                    onClick = {
                        streamViewModel.stopStream()
                        wearablesViewModel.navigateToDeviceSelection()
                    },
                    isDestructive = true,
                    modifier = Modifier.weight(1f),
                )

                CaptureButton(
                    onClick = { streamViewModel.capturePhoto() },
                )
            }
        }
    }

    streamUiState.capturedPhoto?.let { photo ->
        if (streamUiState.isShareDialogVisible) {
            SharePhotoDialog(
                photo = photo,
                onDismiss = { streamViewModel.hideShareDialog() },
                onShare = { bitmap ->
                    streamViewModel.sharePhoto(bitmap)
                    streamViewModel.hideShareDialog()
                },
            )
        }
    }
}
