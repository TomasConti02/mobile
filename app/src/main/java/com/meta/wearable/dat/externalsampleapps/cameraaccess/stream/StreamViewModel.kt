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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive // Opzionale, se usi ancora isActive
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.consumeEach
// ffmpeg -i test3.mp4 -c:v libx265 -c:a aac -tag:v hvc1 -vf "scale=540:960" test_mobility2.mov
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
  private val FRAME_SKIP = 8 // tread off, working on less bitmap without losing accuracy in the result
  private lateinit var frameChannel: Channel<Bitmap>
  private var yoloJob: Job? = null
  private var isYoloRunning = false //NO yolo spam controller
  private var lastState: MotionDetector.State? = null //execute yolo inference only one time after stable state
  private var lastYoloTime = 0L
  private val YOLO_INTERVAL_MS = 1000L
  private val motionDetector = MotionDetectorFactory.getInstance() //get the monitor detector instance
  private var hasDetectedObject = false //into a stable camera state do not create inference loops
  private val _motionState = MutableStateFlow(MotionDetector.State.STILL)
  val motionState: StateFlow<MotionDetector.State> = _motionState.asStateFlow()
  private val _detectedObjects = MutableStateFlow<List<Detection>>(emptyList())
  val detectedObjects: StateFlow<List<Detection>> = _detectedObjects.asStateFlow()
  fun startStream() {
    Log.d(TAG, "startStream: started with quality = MEDIUM, 24 fps")
    viewModelScope.launch { //if the instance is not ready, corutine stop, save the state and the thread keep executing
      yoloDetector = YoloProvider.get(getApplication()) //in case of block runtime wake up corutine as soon as the instance is ready
      Log.d(TAG, "yolo detector initialized")
    }
    streamSession?.close()
    streamSession = null

    videoJob?.cancel()
    stateJob?.cancel()

    presentationQueue?.stop()
    presentationQueue = null

    frameCounter = 0
    frameChannel = Channel<Bitmap>(Channel.CONFLATED) //keep only last bitmap frame (good for buffer efficiency), overwrite if too slow

    yoloJob = viewModelScope.launch(Dispatchers.Default) { //corutine exec on Dispatchers.Default thread pool for cpu intensive operation
      try {
        for (bitmap in frameChannel) { //corutine manage one bitmap at time, wait if there is no one
          try {
            val state = motionDetector.analyze(bitmap) // get the state
            _motionState.value = state
            val now = System.currentTimeMillis()
            val justBecameStable = state == MotionDetector.State.STABLE && lastState == MotionDetector.State.MOVING
            val timeOk = now - lastYoloTime > YOLO_INTERVAL_MS
            if (state == MotionDetector.State.MOVING) {
              hasDetectedObject = false
              _detectedObjects.value = emptyList()
            }
            if (state == MotionDetector.State.STABLE && !hasDetectedObject && (justBecameStable || timeOk)) {
              lastYoloTime = now
              triggerYolo(bitmap)  //trigger yolo inference and get the result
            } else {
              bitmap.recycle() //not for yolo so recycle, avoid memory leak
            }
            lastState = state
          } catch (e: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle() //fail in processing
            Log.e(TAG, "Error during frame processing", e)
          }
        }
      } catch (e: CancellationException) {
        Log.d(TAG, "YOLO Job cancelled")
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

    videoJob = viewModelScope.launch { streamSession.videoStream.collect { handleVideoFrame(it) } } //launch at each frame

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

  private fun triggerYolo(bitmap: Bitmap) {
    if (!isYoloRunning) { //yolo still running, skip the inference no overload
      isYoloRunning = true
      viewModelScope.launch(Dispatchers.Default) { //corutine into a thread pool Dispatchers.Default cpu intensive
        try {
          val detections = yoloDetector?.detect(bitmap) ?: emptyList() // ask to the model the detected classes
          if (detections.isNotEmpty()) {
            hasDetectedObject = true
            _detectedObjects.value = detections //list of detected object form the yolo inference
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
    //PROBLEM we have to send the same bitmap to two diff owners -> presentationQueue + frameChannel (share the pointer/reference in memory)
    //Race Condition is possible -> we need to execute a trade off. with a copy operation more memory heap RAM and cpu usage but safe code (no more race conditions)
    if (bitmap != null) {
      if (frameCounter++ % FRAME_SKIP == 0) { //trade off do not manage all the frames
        if (::frameChannel.isInitialized && !frameChannel.isClosedForSend) {
          val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
          //frameChannel.trySend(safeBitmap)  //send the bitmap even into the frame channel for yolo
          val result = frameChannel.trySend(safeBitmap)

          if (result.isFailure) {
            safeBitmap.recycle()
          }
        }
      }
      //presentationQueue?.enqueue(bitmap, videoFrame.presentationTimeUs)
      val queue = presentationQueue
      if (queue != null) {
        queue.enqueue(bitmap, videoFrame.presentationTimeUs)
      } else {
        bitmap.recycle()
      }
    } else {
      Log.d(TAG, "handleVideoFrame fail")
    }
  }
  /*
  * executing a complete stop streaming and resource cleaning is IMPORTANT to avoid any king of problem after the stream stop
  * function SYNCH and blocking for the view BUT it is ok, even the original code exec a synch stream stop for the view */
  fun stopStream() {
    Log.d(TAG, "stopStream started")
    try {
      videoJob?.cancel()
      videoJob = null

      stateJob?.cancel()
      stateJob = null

      yoloJob?.cancel()
      yoloJob = null

      presentationQueue?.stop()
      presentationQueue = null

      //   !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
      streamSession?.let { session ->
        Log.d(TAG, "StreamSession hardware closed")
        session.close() // clear the buffer of the device (AVOID MANY SCREEN BLOCKING PROBLEMS)
      }
      streamSession = null

      if (::frameChannel.isInitialized) {
        frameChannel.close()
        frameChannel.cancel()
        var leftover = frameChannel.tryReceive().getOrNull()
        while (leftover != null) {
          leftover.recycle()
          leftover = frameChannel.tryReceive().getOrNull()
        }
      }

      frameCounter = 0
      isYoloRunning = false
      hasDetectedObject = false
      lastState = null
      lastYoloTime = 0L

      _motionState.value = MotionDetector.State.STILL
      _detectedObjects.value = emptyList()

      _uiState.update {
        INITIAL_STATE.copy(videoFrame = null)
      }
      motionDetector.clear()
      Log.d(TAG, "stopstream process END UP ")

    } catch (e: Exception) {
      Log.e(TAG, "stopStream ERROR", e)
    }
  }


  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  /////////////////////////////////////////////Under this threshold I do not have touch any code /////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
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

/*
    stateJob = viewModelScope.launch {
      streamSession.state.collect { currentState ->
        val prevState = _uiState.value.streamSessionState
        _uiState.update { it.copy(streamSessionState = currentState) }

        // Se lo stato diventa STOPPED, significa che l'hardware è spento con successo
        if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
          // Avviamo la navigazione in sicurezza da qui
          wearablesViewModel.navigateToDeviceSelection()
        }
      }
    }*/
/*
  fun stopStream() {
    viewModelScope.launch { //asynch by corutine, could be blocking

      presentationQueue?.stop()
      presentationQueue = null

      videoJob?.cancelAndJoin()
      videoJob = null
      if (::frameChannel.isInitialized) {
        frameChannel.close()
        for (bitmap in frameChannel) {
          bitmap.recycle()
        }
      }

      _uiState.update { INITIAL_STATE }

      hasDetectedObject = false
      _detectedObjects.value = emptyList()

      videoJob?.cancelAndJoin()
      videoJob = null

      stateJob?.cancel()
      stateJob = null

      presentationQueue?.stop()
      presentationQueue = null

      Log.d(TAG, "stopstream process END UP")
    }
  }

   */


/*
 fun stopStream() {
   viewModelScope.launch {
     try {
       videoJob?.cancelAndJoin()
       videoJob = null

       stateJob?.cancel()
       stateJob = null

       if (::frameChannel.isInitialized) {
         frameChannel.close()
         frameChannel.cancel()
         for (bitmap in frameChannel) {
           if (!bitmap.isRecycled) {
             bitmap.recycle()
           }
         }
       }
       yoloJob?.cancelAndJoin()
       yoloJob = null

       presentationQueue?.stop()
       presentationQueue = null

       frameCounter = 0

       isYoloRunning = false
       hasDetectedObject = false
       lastState = null
       lastYoloTime = 0L

       _motionState.value = MotionDetector.State.STILL
       _detectedObjects.value = emptyList()

       _uiState.update {
         INITIAL_STATE.copy(videoFrame = null)
       }

       Log.d(TAG, "stopstream process END UP")

     } catch (e: Exception) {

       Log.e(TAG, "stopStream error", e)
     }
   }
 }
  */
/*
fun stopStream() {
  Log.d(TAG, "stopStream iniziato")

  // 1. Cancelliamo i Job immediatamente (Senza Join per non bloccare)
  videoJob?.cancel()
  videoJob = null

  // Cancelliamo lo stateJob così non intercetta cambi di stato spuri durante la pulizia
  stateJob?.cancel()
  stateJob = null

  yoloJob?.cancel()
  yoloJob = null

  // 2. Pulizia canali e memoria
  if (::frameChannel.isInitialized) {
    frameChannel.close()
    frameChannel.cancel()
    // Svuota i residui rimasti nel buffer conflated
    var leftover = frameChannel.tryReceive().getOrNull()
    while (leftover != null) {
      leftover.recycle()
      leftover = frameChannel.tryReceive().getOrNull()
    }
  }

  // 3. Ferma la coda video
  presentationQueue?.stop()
  presentationQueue = null

  // 4. Se la sessione dell'SDK Meta DAT ha un metodo di chiusura, chiamalo qui
  // streamSession?.stop() o simile, se previsto dall'SDK
  streamSession = null

  // 5. Reset completo degli stati della UI
  frameCounter = 0
  isYoloRunning = false
  hasDetectedObject = false
  lastState = null
  lastYoloTime = 0L

  _motionState.value = MotionDetector.State.STILL
  _detectedObjects.value = emptyList()

  _uiState.update {
    INITIAL_STATE.copy(videoFrame = null)
  }

  Log.d(TAG, "stopstream process END UP")

  // 6. Avvisa la UI di cambiare schermata ora che tutto è pulito
  wearablesViewModel.navigateToDeviceSelection()
}

 */