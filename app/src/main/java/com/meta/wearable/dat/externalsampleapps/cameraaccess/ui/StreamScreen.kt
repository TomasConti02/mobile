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
//To the stream screen as always the WearablesViewModel but also streamViewModel for the stream coordination
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
    val motionState by streamViewModel.motionState.collectAsStateWithLifecycle() //added track the view state and show on the view
    val detectedObjects by streamViewModel.detectedObjects.collectAsStateWithLifecycle() //added track the detected objects and boxes shapes
    LaunchedEffect(Unit) {
        streamViewModel.startStream()
    }
    Box(modifier = modifier.fillMaxSize()) {
        streamUiState.videoFrame?.let { videoFrame ->
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = videoFrame.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
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
                        // box
                        drawRect(
                            color = boxColor,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = Stroke(width = 4f)
                        )
                        //labels
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
        Row(
            modifier = Modifier //draw the state
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

        if (detectedObjects.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 80.dp, start = 16.dp)
            ) {
                detectedObjects.forEach { detection ->
                    Text(
                        text = "📷 object=${detection.classId} - confidence:(${(detection.confidence * 100).toInt()}%)",
                        color = Color.Yellow,
                        fontSize = 14.sp
                    )
                }
            }
        }
        if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
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
