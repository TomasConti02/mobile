/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> ARGB conversion)
//   log operation -> adb logcat -v time | grep -iE "MockDeviceKitViewModel|StreamViewModel"
//   ./gradlew clean assembleDebug installDebug
//
/**
 * Project architecture follow the pattern Model–View–ViewModel MVVM using feature based package
 * View (UI) -> observe the state
 * ViewModel -> manage the logic and the state
 * UiState -> immutable ui state
 * StreamViewModel.kt, MockDeviceKitViewModel.kt, WearablesViewModel.kt → ViewModel
 * StreamUiState.kt, MockDeviceKitUiState.kt, WearablesUiState.kt →  UI (Model per la View) state
 * HomeScreen.kt, StreamScreen.kt, ecc. → View (UI, Jetpack Compose)
 * /stream -> keep everything manage the stream
 * /mockdevicekit -> another feature for mock testing
 * /ui -> keeo all the ui classes
 * Starting from the UI screen where the user can interact with HomeScreen.kt | StreamScreen.kt | MockDeviceKitScreen.kt
 *
 * The user action on the UI screen triggers view model feature/functions. CameraAccessScaffold is the anchor of the View
 * Based on the UiState change the activity/view to show up to the user
 *
 * All the view model components manage the logic behind the system and the orchestration as well
 *
 * Utente (UI)
 *    ↓
 * Evento (click, action)
 *    ↓
 * ViewModel
 *    ↓
 * Processing (detector, converter, ecc.)
 *    ↓
 * Nuovo UiState
 *    ↓
 * UI si aggiorna automaticamente
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

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
import com.example.motion.MotionDetector
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.consumeEach
//StreamViewModel -> class. receive data from IoT device, stream the data stream and execute a sample YOLO object detection
// this class keep inside the business logic of the wear able device
// ffmpeg -i test3.mp4 -c:v libx265 -c:a aac -tag:v hvc1 -vf "scale=540:960" test_mobility2.movclear
// adb push test_mobility2.mov /sdcard/Download/
class StreamViewModel( application: Application, private val wearablesViewModel: WearablesViewModel, ) : AndroidViewModel(application) {
  companion object {  private const val TAG = "StreamViewModel"
    private val INITIAL_STATE = StreamUiState()  }
  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var streamSession: StreamSession? = null //stream connection
  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow() //what the stream scree look
  private var videoJob: Job? = null
  private var stateJob: Job? = null
  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null
  //ADDED
  private var yoloDetector: YoloDetector? = null //my new neural network for object detection
  private var frameCounter = 0 //frame skipping
  private val FRAME_SKIP = 4
  //CONFLATED mea
  private lateinit var frameChannel: Channel<Bitmap> //async queue channel for components communication
  //job della coroutine che gira su Dispatchers.Default e prende i frame dal frameChannel per passarli a YOLO.
  private var yoloJob: Job? = null
  private var isYoloRunning = false //NO yolo spam controller
  private var lastState: MotionDetector.State? = null //execute yolo inference only one time after stable state
  private var lastYoloTime = 0L
  private val YOLO_INTERVAL_MS = 1000L
  private var motionDetector: MotionDetector? = null //////////////////////////////////////////////////////////////////////////////////////////
  private var hasDetectedObject = false // if the state is stable and we detect the object sto the yolo inference for the stable camera period
  //MVVM is the pattern
  //ViewModel -> as this class manage the state. StreamViewModel -> is the data producer
  // UI (StreamScreen) ->  as streamscreen observe the state -> is the data consumer
  private val _motionState = MutableStateFlow(MotionDetector.State.STILL) //state mutable and observable variable for MVVM
  val motionState: StateFlow<MotionDetector.State> = _motionState.asStateFlow() //read only variable from the UI
  private val _detectedObject = MutableStateFlow<String?>(null) //state mutable and observable variable for MVVM
  val detectedObject: StateFlow<String?> = _detectedObject.asStateFlow()
  fun startStream() {
    Log.d(TAG, "startStream: avvio stream con qualità MEDIUM, 24 fps")
    // cancel job Coroutine, background process
    //state cleaning before run
    videoJob?.cancel()
    stateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null
    //SYNC inizialization att, because  yoloDetector hva to load a heavy inference model to execute. Background service and state update needed
    if (yoloDetector == null) { yoloDetector = YoloDetector(getApplication()) }
    if (motionDetector == null) { motionDetector = MotionDetector()  } ////////////////////////////////////////////////////////////////////////////
    frameCounter = 0
    //CONFLATED non voglio troppo accordamento, se l'immagine arriva prima della fine dell'elaborazione sovrascrivi
    frameChannel = Channel<Bitmap>(Channel.CONFLATED) //keep only last bitmap frame (good for buffer efficiency )
    /*
    viewModelScope.launch: Avvia un'operazione asincrona (Coroutine) legata alla vita del ViewModel. Se chiudi la schermata dell'app, questo processo si ferma automaticamente per non sprecare batteria.
    Channel.CONFLATED aiuta ad impedire che il canale accodi frame che non rispettanoil timing dell'inferenza*/
    /*
    Dispatchers.Default: Indica al sistema di eseguire i calcoli su un pool thread dedicato alle operazioni pesanti per la CPU. È fondamentale per non bloccare l'interfaccia grafica (UI).*/
    yoloJob = viewModelScope.launch(Dispatchers.Default ) { //offloading operation
      /*for (bitmap in frameChannel): Questo ciclo è "sospensivo". Significa che non fa nulla finché nel frameChannel non arriva una nuova immagine. Non appena l'immagine arriva, entra nel ciclo e la elabora*/
      for (bitmap in frameChannel) {
        try {
          if (!isActive) {
            bitmap.recycle()
            break //no job exit
          }
          val start = System.currentTimeMillis()
          //no another launch, because "for (bitmap in frameChannel) {" could keep in loop and overload coroutine launch, heap overload !!!!!!!!!!!!!!!!
          val state = motionDetector?.analyze(bitmap) // output of the scena state monitored
          _motionState.value = state ?: MotionDetector.State.STILL
          val duration = System.currentTimeMillis() - start
          if (state == MotionDetector.State.MOVING) {
            hasDetectedObject = false // 🔁 reset quando torna movimento
            _detectedObject.value = null
          }
          Log.d(TAG, "Monitor detector : ${state} in ${duration}ms")
          val now = System.currentTimeMillis()
          val justBecameStable =
            state == MotionDetector.State.STABLE && lastState == MotionDetector.State.MOVING
          val timeOk = now - lastYoloTime > YOLO_INTERVAL_MS
          if (state == MotionDetector.State.STABLE && !hasDetectedObject && (justBecameStable || timeOk)) {
            lastYoloTime = now
            triggerYolo(bitmap)
          }
          lastState = state
          bitmap.recycle()
        } catch (e: Exception) {
          Log.e(TAG, "Errore YOLO frame", e)
        }
      }
    }
    //this queue allow to collect the stream frame and execute the presentation is the right order based on the time stamp
    // Initialize presentation queue - frames are presented based on timestamp, not arrival time !!!!! BECAUSE THE ORDER QoS
    // Uses IntArray pooling for efficiency - cheaper than Bitmap.copy()
    val queue = PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame -> //WHERE A FRAME ARRIVE TO THE QUEUE UPDATE THE UI STATE
              // This is called from the presentation thread at regular intervals
              // when a frame's presentation time has arrived
              //Log.d(TAG, "Frame mostrato a schermo, conteggio frame = ${_uiState.value.videoFrameCount + 1}")
              _uiState.update { it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)  }
            },
        )
    presentationQueue = queue
    queue.start() //start the queue
    //HOW DATA FROM FROM THE HW device TO Model and at the end to the VIEW, everything execute in parallel by Job (coroutine)
    // start the stream
    val streamSession = Wearables.startStreamSession(
                getApplication(),
                deviceSelector, //sdk that select the device available
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24), ).also { streamSession = it }
    //launch -> start a parallel corutine jobs for async tracking
    // this job receive frame/sample videos from the connection streamSession
    videoJob = viewModelScope.launch { streamSession.videoStream.collect { handleVideoFrame(it) } } //send the collected bitmap frame to the handleVideoFrame function
    // this job receive state stream changes from the connection streamSession
    stateJob = viewModelScope.launch {  streamSession.state.collect { currentState ->
            Log.d(TAG, "Stato stream cambiato: ${currentState.name}")
            val prevState = _uiState.value.streamSessionState
            _uiState.update { it.copy(streamSessionState = currentState) }
            // navigate back when state transitioned to STOPPED
            if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
        } //stateJob end
    Log.d(TAG, "startStream: session created, videoJob e stateJob avviati")
  } //start stream end
  private fun triggerYolo(bitmap: Bitmap) {
    if (!isYoloRunning) {
        val yoloBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        isYoloRunning = true //stop other yolo spam detection
        viewModelScope.launch(Dispatchers.Default) { //another launch, keep the main for channel component monitoring
          val start = System.currentTimeMillis()
          val detections = yoloDetector?.detect(yoloBitmap) ?: emptyList()

          val duration = System.currentTimeMillis() - start
          yoloBitmap.recycle()
          if (detections.isNotEmpty()) {
            hasDetectedObject = true //  blocca future inferenze
            val classId = detections.getOrNull(0)?.classId ?: "NULL"
            Log.d(TAG, "🎯 YOLO: ${detections.size} in ${duration}ms, classId=$classId")
            _detectedObject.value = classId?.toString()
            //Log.d(TAG, "🎯 YOLO: ${detections.size} in ${duration}ms, ${detections[0]}")
          }
          isYoloRunning = false //reactivate yolo detection
      }
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) { //execute every time a frame arrive from the camera strema
    //data receive from the camera stream of the devices, convert the YUV formate into a bitmap more suitable
    val bitmap = YuvToBitmapConverter.convert(videoFrame.buffer, videoFrame.width, videoFrame.height)
    if (bitmap != null) {
      if (frameCounter++ % FRAME_SKIP == 0) {
        if (::frameChannel.isInitialized && !frameChannel.isClosedForSend) {
          val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) /////////////////////////////ATTEETETTETETETETETNTION
          frameChannel.trySend(safeBitmap)
          //frameChannel.trySend(bitmap) //asynch photo sending to the channel
        }
      }
      //i send all the frame to the presentation queue that act as a frame tie based stable queue
      presentationQueue?.enqueue(bitmap, videoFrame.presentationTimeUs)
    } else {
      Log.e(TAG, "YUV -> Bitmap failed")
    }
  }
/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  fun stopStream() {
    //problem of memory leak, what happen when the yolo/detectior process try to use a data delated
    viewModelScope.launch { //all into a light viewmodel corutine, avoid the UI Thread block and keep the screan visible
      //cancelAndJoin stop the execution after the last dataframe
      videoJob?.cancelAndJoin()
      videoJob = null
      // close the frame channel
      if (::frameChannel.isInitialized) {
        frameChannel.close() //frame buffer closing after the flow frame stop
      }
      // wait for the yolo job closing
      yoloJob?.cancelAndJoin()
      yoloJob = null

      yoloDetector?.close() //closing of yolo detector, and the interpreter as well
      yoloDetector = null
      _uiState.update { INITIAL_STATE } //refresh the ui
    }
  }
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
