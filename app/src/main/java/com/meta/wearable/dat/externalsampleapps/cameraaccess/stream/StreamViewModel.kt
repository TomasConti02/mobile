/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream
import com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo.*
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers //ADDED
import kotlinx.coroutines.channels.Channel//
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.consumeEach
//StreamViewModel -> class. receive data from IoT device, stream the data stream and execute a sample YOLO object detection
// this class keep inside the business logic of the wear able device
// ffmpeg -i test3.mp4 -c:v libx265 -c:a aac -tag:v hvc1 -vf "scale=540:960" test_mobility2.movclear
// adb push test_mobility2.mov /sdcard/Download/
class StreamViewModel( application: Application, private val wearablesViewModel: WearablesViewModel, ) : AndroidViewModel(application) {
  companion object {  private val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()  }
  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var streamSession: StreamSession? = null
  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()
  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var presentationQueue: PresentationQueue? = null
  private var yoloDetector: YoloDetector? = null //yolo detector
  private var frameCounter = 0
  private val FRAME_SKIP = 6 // tread off, working on less bitmap without losing accuracy in the result
  private lateinit var frameChannel: Channel<Bitmap>
  private var yoloJob: Job? = null
  private var isYoloRunning = false //NO yolo spam controller
  private var lastState: MotionDetector.State? = null //execute yolo inference only one time after stable state
  private var lastYoloTime = 0L
  private val YOLO_INTERVAL_MS = 1000L
  private val motionDetector = MotionDetectorFactory.getInstance()
  private var hasDetectedObject = false //into a stable camera state do not create inference loops
  private val _motionState = MutableStateFlow(MotionDetector.State.STILL) //state mutable of the monitor detector
  val motionState: StateFlow<MotionDetector.State> = _motionState.asStateFlow() //read only variable from the view
  private val _detectedObjects = MutableStateFlow<List<Detection>>(emptyList())
  val detectedObjects: StateFlow<List<Detection>> = _detectedObjects.asStateFlow()
  fun startStream() {
    Log.d(TAG, "startStream: started with quality = MEDIUM, 24 fps")

    viewModelScope.launch { //if the instance is not ready, corutine stop, save the state and the thread keep executing
      yoloDetector = YoloProvider.get(getApplication()) //in case of block runtime wake up corutine as soon as the instance is ready
    }

    videoJob?.cancel()
    stateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null

    frameCounter = 0
    frameChannel = Channel<Bitmap>(Channel.CONFLATED) //keep only last bitmap frame (good for buffer efficiency), overwrite if too slow

    yoloJob = viewModelScope.launch(Dispatchers.Default ) { //life cycle scope ViewModel, and execute into Dispatchers.Default thread pool for cpu intensive operations
      for (bitmap in frameChannel) { //corutine suspend if there is no bitmap on the channel and let free the thread
        try {
          if (!isActive) { //is the job still active ?
            bitmap.recycle() //no memory leak, recycle the memory of the bitmap
            break //no job exit
          }
          val start = System.currentTimeMillis()
          val state = motionDetector.analyze(bitmap)
          _motionState.value = state //trigger the view thi the new monitor detector state update
          val duration = System.currentTimeMillis() - start
          if (state == MotionDetector.State.MOVING) { //reset all
            hasDetectedObject = false
            _detectedObjects.value = emptyList()
          }
          Log.d(TAG, "Monitor detector : ${state} in ${duration}ms")
          val now = System.currentTimeMillis()
          val justBecameStable = state == MotionDetector.State.STABLE && lastState == MotionDetector.State.MOVING
          val timeOk = now - lastYoloTime > YOLO_INTERVAL_MS
          if (state == MotionDetector.State.STABLE && !hasDetectedObject && (justBecameStable || timeOk)) {
            lastYoloTime = now
            triggerYolo(bitmap)
          }else{
            //only if we did not pass to yolo detector
            bitmap.recycle() //recycle the memory because memory leak
          }
          lastState = state
        } catch (e: Exception) {
          Log.e(TAG, "Error into the monitor loop", e)
        }
      }
    }

    val queue = PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              _uiState.update { it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)  } },)
    presentationQueue = queue
    queue.start()

    val streamSession = Wearables.startStreamSession(
                getApplication(),
                deviceSelector,
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24), ).also { streamSession = it }

    videoJob = viewModelScope.launch { streamSession.videoStream.collect { handleVideoFrame(it) } }

    stateJob = viewModelScope.launch {  streamSession.state.collect { currentState ->
            val prevState = _uiState.value.streamSessionState
            _uiState.update { it.copy(streamSessionState = currentState) }
            if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
        } //stateJob end

  } //start stream end
  /*
  private fun triggerYolo(bitmap: Bitmap) {
    if (!isYoloRunning) {
        val yoloBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        isYoloRunning = true //stop other yolo spam detection
        viewModelScope.launch(Dispatchers.Default) { //another launch, keep the main for channel component monitoring
          val start = System.currentTimeMillis()
          val detections = yoloDetector?.detect(yoloBitmap) ?: emptyList()//YOLO OBJECT DETECTION
          val duration = System.currentTimeMillis() - start
          Log.d(TAG, "YOLO inference operation  ${duration}ms")
          bitmap.recycle()
          if (detections.isNotEmpty()) {
            hasDetectedObject = true //  blocca future inferenze
            _detectedObjects.value = detections
          }
          isYoloRunning = false //reactivate yolo detection
      }
    }
  }*/
  private fun triggerYolo(bitmap: Bitmap) {
    if (!isYoloRunning) { //yolo busy still running, skip the inference no overload
      isYoloRunning = true
      viewModelScope.launch(Dispatchers.Default) {
        try {
          val detections = yoloDetector?.detect(bitmap) ?: emptyList()
          if (detections.isNotEmpty()) {
            hasDetectedObject = true
            _detectedObjects.value = detections
          }
        } finally {
          bitmap.recycle() //alter the yolo inference clean the bitmap heap ram memory
          isYoloRunning = false
        }
      }
    } else {
      bitmap.recycle() //busy yolo inference no memory leak
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) { //execute every time a frame arrive from the camera stream
    val bitmap = YuvToBitmapConverter.convert(videoFrame.buffer, videoFrame.width, videoFrame.height) //for visualization needed YuvToBitmapConverter
    //PROBLEM we have to send the same bitmap to two diff owners -> presentationQueue + frameChannel
    //Race Condition is possible -> we need to execute a trade off. with a copy operation more memory heap RAM and cpu usage but safe code(no more race conditions)
    if (bitmap != null) {
      if (frameCounter++ % FRAME_SKIP == 0) { //trade off do not manage all the frames
        if (::frameChannel.isInitialized && !frameChannel.isClosedForSend) {
          val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
          //val safeBitmap = Bitmap.createScaledBitmap(bitmap, 640, 640, false) -> can be interesting
          //val safeBitmap = Bitmap.createScaledBitmap(bitmap, 320, 320, false) -> can be also interesting
          frameChannel.trySend(safeBitmap)
          //frameChannel.trySend(bitmap)
        }
      }
      presentationQueue?.enqueue(bitmap, videoFrame.presentationTimeUs)
    } else {
      Log.e(TAG, "YUV -> Bitmap failed")
    }
  }

  fun stopStream() {
    viewModelScope.launch {
      videoJob?.cancelAndJoin()
      videoJob = null
      if (::frameChannel.isInitialized) {
        frameChannel.close()
      }
      yoloJob?.cancelAndJoin()
      yoloJob = null
      _uiState.update { INITIAL_STATE }
    }
  }
  //////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////////
  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }
    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        streamSession
          ?.capturePhoto()
          ?.onSuccess { photoData ->
            Log.d(TAG, "Photo capture successful")
            handlePhotoData(photoData)
            _uiState.update { it.copy(isCapturing = false) }
          }
          ?.onFailure { error, _ ->
            Log.e(TAG, "Photo capture failed: ${error.description}")
            _uiState.update { it.copy(isCapturing = false) }
          }
      }
    } else {
      Log.w(
        TAG,
        "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }
  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    stateJob?.cancel()
    MotionDetectorFactory.release()
  }

  class Factory( private val application: Application, private val wearablesViewModel: WearablesViewModel, ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }


}
