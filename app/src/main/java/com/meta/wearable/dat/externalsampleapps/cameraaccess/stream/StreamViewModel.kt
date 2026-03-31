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
import kotlinx.coroutines.channels.consumeEach
//StreamViewModel -> class. receive data from IoT device, stream the data stream and execute a sample YOLO object detection
// this class keep inside the business logic of the wear able device
class StreamViewModel( application: Application, private val wearablesViewModel: WearablesViewModel, ) : AndroidViewModel(application) {
  companion object {
    private const val TAG = "StreamViewModel" //  for logging
    private val INITIAL_STATE = StreamUiState()
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var streamSession: StreamSession? = null //stream connection

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null
  //ADDED
  private var yoloDetector: YoloDetector? = null //my new neural network for object detection
  private var frameCounter = 0
  private val FRAME_SKIP = 24  // for yolo
  //CONFLATED mea
  private var frameChannel: Channel<Bitmap>? = null

  fun startStream() {
    Log.d(TAG, "startStream: avvio stream con qualità MEDIUM, 24 fps")
    // cancel job Coroutine, background process
    //state cleaning before run
    videoJob?.cancel()
    stateJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null
    //ADDEDE
    if (yoloDetector == null) {
      yoloDetector = YoloDetector(getApplication()) // creation of the model
    }
    frameChannel = Channel(Channel.CONFLATED)
    //this queue allow to collect the stream frame and execute the presentation is the right order based on the time stamp
    // Initialize presentation queue - frames are presented based on timestamp, not arrival time !!!!! BECAUSE THE ORDER QoS
    // Uses IntArray pooling for efficiency - cheaper than Bitmap.copy()
    val queue = PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              // This is called from the presentation thread at regular intervals
              // when a frame's presentation time has arrived
              //Log.d(TAG, "Frame mostrato a schermo, conteggio frame = ${_uiState.value.videoFrameCount + 1}")
              _uiState.update { it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)  }
            },
        )
    presentationQueue = queue
    queue.start() //start the queue
    // start the stream
    val streamSession = Wearables.startStreamSession(
                getApplication(),
                deviceSelector,
                StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24), ).also { streamSession = it }
    // this job receive frame/sample videos
    videoJob = viewModelScope.launch { streamSession.videoStream.collect { handleVideoFrame(it) } }
    // this job receive state stream changes
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
    ////////////////////////////////////////////////////////////////////////////////


    viewModelScope.launch(Dispatchers.Default) {
      for (bitmap in frameChannel) {
        try {
          val detections = yoloDetector?.detect(bitmap) ?: emptyList()
          Log.d(TAG, "🎯 YOLO rilevati ${detections.size} oggetti")
        } catch (e: Exception) {
          Log.e(TAG, "Errore YOLO", e)
        }
      }
    }





    //////////////////////////////////////////////////////////////////////////////
  } //start stream end

  fun stopStream() {
    //Log.d(TAG, "stopStream: chiusura stream in corso")
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    presentationQueue?.stop()
    presentationQueue = null
    streamSession?.close()
    streamSession = null
    _uiState.update { INITIAL_STATE }
    //Log.d(TAG, "stopStream: stream terminato")
    //ADDED
    frameChannel.close()
    yoloDetector?.close()
    yoloDetector = null
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

  // QUI DENTRO METTIAMO UNA RETE neurale YOLO che elabora in stream il video
  // riduzione dei frame, uno ogni tre da parte della network
  /*
  Il punto di inserimento più equilibrato è dopo la conversione a bitmap
  (handleVideoFrame), con una coroutine su Dispatchers.Default e un meccanismo di
  frame skipping per non sovraccaricare il sistema. Questo mantiene lo
  streaming fluido e l’UI reattiva, mentre i risultati dell’elaborazione
  possono essere utilizzati per overlay o altre logiche.
  * */
  /*
  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    // Use optimized YuvToBitmapConverter for direct I420 to ARGB conversion
    Log.d(TAG, "handleVideoFrame: ${videoFrame.width}x${videoFrame.height}, " +
            "timestamp=${videoFrame.presentationTimeUs}")
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      Log.d(TAG, "Frame convertito con successo, enqueued")
      presentationQueue?.enqueue(
          bitmap,
          videoFrame.presentationTimeUs,
      )
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }
*/
  // executed for each video sample coming from the IoT weare able devices
  private fun handleVideoFrame(videoFrame: VideoFrame) {
    //Log.d(TAG, "handleVideoFrame: ${videoFrame.width}x${videoFrame.height}, " + "timestamp=${videoFrame.presentationTimeUs}")
    //the frame come into a bat format YUV and convert into images Bitmap
    //Bitmap.createScaledBitmap(bitmap, 320, 320, true) -> in production
    val bitmap = YuvToBitmapConverter.convert(videoFrame.buffer, videoFrame.width, videoFrame.height,)
    if (bitmap != null) {
      //Log.d(TAG, "Frame convertito con successo, enqueued")
      // Frame skipping: elabora solo 1 frame ogni FRAME_SKIP
      if (frameCounter++ % FRAME_SKIP == 0 ) {
        //executed in back end
        /*
        viewModelScope.launch(Dispatchers.Default) {
          try {
            val detections = yoloDetector?.detect( bitmap ) ?: emptyList()
            Log.d(TAG, "🎯 YOLO rilevati ${detections.size} oggetti")
            // Opzionale: aggiorna lo stato per mostrarli nell'UI
            // _uiState.update { it.copy(detections = detections) }
          } catch (e: Exception) {
            Log.e(TAG, "Errore durante l'inferenza", e)
          }
        }
        */

        ///////////////////////////////////////////////////
        frameChannel?.trySend(bitmap)
        ///////////////////////////////////////////////////
      }
      presentationQueue?.enqueue(bitmap, videoFrame.presentationTimeUs)
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
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
