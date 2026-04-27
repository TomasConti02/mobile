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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo.MotionDetector
import androidx.compose.ui.graphics.nativeCanvas

//@compose function and not a class, used to show up the wearable video stream
//it is a view UI part of Jetpack Compose
// used into Activity or navigation graph
//To the stream screen as allways the WearablesViewModel but also streamViewModel for the stream coordination
@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel = viewModel(
        factory = StreamViewModel.Factory(
            application = (LocalActivity.current as ComponentActivity).application,
            wearablesViewModel = wearablesViewModel,
        ),
    ),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
    val motionState by streamViewModel.motionState.collectAsStateWithLifecycle()
    val detectedObjects by streamViewModel.detectedObjects.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        streamViewModel.startStream()
    }

    Box(modifier = modifier.fillMaxSize()) {

        streamUiState.videoFrame?.let { videoFrame ->

            Box(modifier = Modifier.fillMaxSize()) {

                // 🎥 VIDEO
                Image(
                    bitmap = videoFrame.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // 🎯 OVERLAY BOXES
                Canvas(modifier = Modifier.fillMaxSize()) {

                    val frameWidth = videoFrame.width.toFloat()
                    val frameHeight = videoFrame.height.toFloat()

                    val scaleX = size.width / frameWidth
                    val scaleY = size.height / frameHeight

                    detectedObjects.forEach { detection ->

                        val rect = detection.boundingBox

                        val left = rect.left * scaleX
                        val top = rect.top * scaleY
                        val right = rect.right * scaleX
                        val bottom = rect.bottom * scaleY

                        val boxColor = when {
                            detection.confidence > 0.8f -> Color.Green
                            detection.confidence > 0.5f -> Color.Yellow
                            else -> Color.Red
                        }

                        // 🟥 BOX
                        drawRect(
                            color = boxColor,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = Stroke(width = 4f)
                        )

                        // 🏷 LABEL
                        drawIntoCanvas { canvas ->
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 40f
                                isFakeBoldText = true
                            }

                            canvas.nativeCanvas.drawText(
                                "id=${detection.classId} ${(detection.confidence * 100).toInt()}%",
                                left,
                                (top - 10).coerceAtLeast(40f),
                                paint
                            )
                        }
                    }
                }
            }
        }

        // 🔝 STATO MOVIMENTO
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 24.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = when (motionState) {
                    MotionDetector.State.MOVING -> "🔴 MOVING"
                    MotionDetector.State.STILL -> "🟡 STILL"
                    MotionDetector.State.STABLE -> "🟢 STABLE"
                },
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 📦 DEBUG LIST
        if (detectedObjects.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 80.dp, start = 16.dp)
            ) {
                detectedObjects.forEach { detection ->
                    Text(
                        text = "📷 id=${detection.classId} (${(detection.confidence * 100).toInt()}%)",
                        color = Color.Yellow,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // ⏳ LOADING
        if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // ⬇️ CONTROLLI
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
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

    // 📤 SHARE
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
/*
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
        //data class Detection(val classId: Int, val confidence: Float, val boundingBox: RectF)
        //_detectedObject = MutableStateFlow<Detection?>(null)
    val detectedObject by streamViewModel.detectedObject.collectAsStateWithLifecycle() //ADDED, it is a list of Detection from the model now

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
*/