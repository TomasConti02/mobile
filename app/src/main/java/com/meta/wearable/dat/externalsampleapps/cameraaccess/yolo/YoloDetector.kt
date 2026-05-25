package com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
// interpreter allow to manage model input and output data
import org.tensorflow.lite.Interpreter //model class for inference
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import org.tensorflow.lite.gpu.CompatibilityList

import org.yaml.snakeyaml.Yaml

import org.opencv.android.OpenCVLoader
///
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
// https://www.tensorflow.org/api_docs/python/tf/lite
//yolo predict model=yolov8n_saved_model/yolov8n_int8.tflite source=0 imgsz=640 int8 ////////////inference model test
//yolo export model=yolov8n.pt format=tflite int8 /////////////exporting command
///https://docs.ultralytics.com/it/datasets/detect/coco/ //////dataset train
// https://yolov8.com/
// https://colab.research.google.com/github/roboflow-ai/notebooks/blob/main/notebooks/train-yolov8-object-detection-on-custom-dataset.ipynb#scrollTo=jbVjEtPAkz3j

data class Detection(val classId: String, val confidence: Float, val boundingBox: RectF)
class YoloDetector(private val context: Context, modelFilename: String = "yolov8n_float32.tflite") { //yolov8n_float32.tflite default model to load
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var interpreter: Interpreter? = null //A Interpreter encapsulates a pre-trained TensorFlow Lite model, in which operations are executed for model inference
    private val inputSize = 640 // yolo need a 640x640 as input activation (a bitmap reshape in needed)
    private val numClasses = 80 //pretrain model classes
    private val numDetections = 8400 //yolo inference output bounding boxes
    private val maxDetections = 20 // max yolo items into a frame
    private val confThreshold = 0.7f //for object detection I want a model enough source
    private val iouThreshold = 0.5f // threshold because bounding box overlap filtering

    private var lastSaveTime: Long = 0
    //private var log = true
    private val saveInterval: Long = 3000
    private var outputBuffer: Array<Array<FloatArray>> = Array(1) { Array(84) { FloatArray(numDetections) } } //output buffer pre allocation
    private val imageProcessor = ImageProcessor.Builder() // Wrapper for normalization operations
        //bit map -> width: 540 , height: 960  -> but the yolo activation input is 640x640 so a reshape needed
        //tensor image -> width: 640 , height: 640
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR)) //bit map reshape from 540x960 to 640x640
        .add(NormalizeOp(0f, 255f)) // Pixel normalization 0-255 a 0-1
        .build()
    private var gpuDelegate: GpuDelegate? = null
    private var labels: Map<Int, String> = emptyMap()
    private val tensorImage = TensorImage(DataType.FLOAT32)
    init {
        try {
            if (OpenCVLoader.initDebug()) {
                Log.d("YoloDetector", "OpenCV SDK loaded")
            } else {
                Log.e("YoloDetector", "OpenCV SDK loading problems")
            }
            loadMetadata("metadata_yolov8n_int8.yaml") //metadata about inference classes are the same for both the same
            var interpreterOptions = Interpreter.Options()
            try {
                //throw IllegalStateException("Test fall back 1")
                val modelBuffer = loadModelFile(context, modelFilename) // load the model weights (from documentation)
                val delegateOptions = GpuDelegate.Options().apply {
                    setPrecisionLossAllowed(true) //speed up but less precision
                    setInferencePreference( //keep stable inference performance, no too much speak
                        GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
                    )
                    setSerializationParams( //cache the model
                        context.cacheDir.absolutePath,
                        "yolo_v8_$modelFilename"
                    )
                }
                gpuDelegate = GpuDelegate(delegateOptions)
                //throw IllegalStateException("Test fall back 2")
                interpreterOptions = Interpreter.Options().apply {
                    addDelegate(gpuDelegate)
                    //setNumThreads(1)
                }
                interpreter = Interpreter(modelBuffer, interpreterOptions) //create the interpreter with model and options
                Log.d("YoloDetector", "Using GPU acceleration")
            } catch (e: Exception) { //if some error with gpu start with cpu and multi thread

                val fallbackModel = "yolov8n_int8.tflite" //smaller version, less precise but faster
                val modelBuffer = loadModelFile(context, fallbackModel)
                Log.w("YoloDetector", "GPU failed → CPU fallback: ${e.message}")
                gpuDelegate?.close()
                gpuDelegate = null
                interpreterOptions = Interpreter.Options().apply {
                    setNumThreads(
                        Runtime.getRuntime().availableProcessors().coerceAtMost(4)
                    )
                }
                interpreter = Interpreter(modelBuffer, interpreterOptions)

            }
            interpreter?.let { tfInterpreter ->
                tfInterpreter.allocateTensors()
                // gpu warm up
                try {
                    val dummyInput = Array(1) { Array(640) { Array(640) { FloatArray(3) } } }
                    val dummyOutput = Array(1) { FloatArray(8400 * 85) }
                    tfInterpreter.run(dummyInput, dummyOutput)
                } catch (_: Exception) {}
                val inputTensor = tfInterpreter.getInputTensor(0) //check the activation input setting
                Log.i("YoloDetector", """
                $modelFilename loaded!
                - Data Type: ${inputTensor.dataType()}
                - Shape: ${inputTensor.shape().contentToString()}
                - Hardware: ${if (gpuDelegate != null) "GPU" else "CPU"}
            """.trimIndent())
            }
        } catch (e: Exception) {
            Log.e("YoloDetector", "ERROR into the yolo detector set up", e)
        }
    }
    private fun loadMetadata(fileName: String) {
        try {
            context.assets.open(fileName).use { inputStream ->
                val yaml = Yaml()
                val data = yaml.load<Map<String, Any>>(inputStream)
                @Suppress("UNCHECKED_CAST")
                val extractedNames = data["names"] as? Map<Int, String>
                if (extractedNames != null) {
                    labels = extractedNames
                }
                Log.i("YoloDetector", """
                --- METADATA LOADED ($fileName) ---
                Description: ${data["description"] ?: "N/A"}
                Author:      ${data["author"] ?: "N/A"}
                Date:        ${data["date"] ?: "N/A"}
                Version:     ${data["version"] ?: "N/A"}
                Task:        ${data["task"] ?: "N/A"}
                Stride:      ${data["stride"] ?: "N/A"}
                Img Size:    ${data["imgsz"] ?: "N/A"}
                Batch:       ${data["batch"] ?: "N/A"}
                Classes:     ${labels.size} labels loaded.
                -----------------------------------
            """.trimIndent())
                if (labels.isNotEmpty()) {
                    val firstClasses = labels.entries.take(3).joinToString { "${it.key}: ${it.value}" }
                    Log.d("YoloDetector", "First classes: $firstClasses...")
                }
            }
        } catch (e: Exception) {
            Log.e("YoloDetector", "ERROR YAML loading", e)
        }
    }
    fun getClassName(classId: Int): String {
        return labels[classId] ?: "Unknown"
    }
    //main operation of yolo called by the model, passing the bitmap frame to detect, output  list of yolo Detected objects
    fun detect(bitmap: Bitmap): List<Detection> {
        val interp = interpreter ?: return emptyList()
        tensorImage.load(bitmap)    //wrapper receive the bitmap
        val processed = imageProcessor.process(tensorImage) //preprocessing
        // save original image shape for remap
        val imgW = bitmap.width
        val imgH = bitmap.height
        try {
            interp.run(processed.buffer, outputBuffer) // inference yolo operation, into outputBuffer there the output
            //batch compose by only one images
            val rawDetections = extractDetections(outputBuffer[0], imgW, imgH) //because we have only one inference bach(image) ->outputBuffer[0]
            val finalDetections = nonMaxSuppression(rawDetections)//filtring
            Log.d("YoloDetector", "detected  ${finalDetections.size} items")
            val currentTime = System.currentTimeMillis()
            if (finalDetections.isNotEmpty() && (currentTime - lastSaveTime > saveInterval)) {
                lastSaveTime = currentTime

                val bitmapToProcess = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                saveScope.launch {
                    for (det in finalDetections) {
                        saveCrop(bitmapToProcess, det)
                    }
                    bitmapToProcess.recycle()
                }
            }
            return finalDetections
        } catch (e: Exception) {
            Log.e("YoloDetector", "Inference failed", e)
            return emptyList()
        }
    }
    private fun extractDetections(output: Array<FloatArray>, imgW: Int, imgH: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        //input shape image 540x960, the yolo activation is 640x640 (the output based on this scale)
        //i need scale factors to bring back the coordinate to the original ones
        //map yolo pixel position in the original images
        val scaleX = imgW.toFloat() / inputSize
        val scaleY = imgH.toFloat() / inputSize
        //for each output box we get an items probability for each dataset COCO classes
        // the code is a O(N^2) very bad but the number are fixed and numClasses is very small -> 80
        for (i in 0 until numDetections) {  // yolo output box images
            var maxClassConf = -1f
            var classId = -1
            for (c in 0 until numClasses) { // for each blocks check the class probability distribution
                val score = output[c + 4][i]
                if (score > maxClassConf) { // keep the most probable class caming from the box
                    maxClassConf = score
                    classId = c
                }
            }
            //tune the threshold in order to avoid outliers classification
            if (maxClassConf > confThreshold) { //keep the class with the max prob for each box and get it if overcame a threshold for prob
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]
                // real shape
                val realW = w * inputSize * scaleX
                val realH = h * inputSize * scaleY
                //i don't want items outside the receptive field of the camera
                // MY FILTERING DECISION
                if (realW >= 100f && realH >= 100f) { //try to filter outliers items boxes
                    //extract the box shape and the box center
                    val realX = cx * inputSize * scaleX
                    val realY = cy * inputSize * scaleY
                    val left = (realX - realW / 2)
                    val top = (realY - realH / 2)
                    val right = (realX + realW / 2)
                    val bottom = (realY + realH / 2)
                    //detections.add( Detection(classId, maxClassConf,
                    // RectF(left.coerceAtLeast(0f), top.coerceAtLeast(0f), right.coerceAtMost(imgW.toFloat()), bottom.coerceAtMost(imgH.toFloat()))) )
                    detections.add( Detection(getClassName(classId), maxClassConf,
                        RectF(left.coerceAtLeast(0f), top.coerceAtLeast(0f), right.coerceAtMost(imgW.toFloat()), bottom.coerceAtMost(imgH.toFloat()))))
                }
            }
        }
        return detections //output the list of detected items box with id, mx conf and original shape
    }
    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> { //algorithm for box detection filtering (box overlapping ecc)
        if (detections.isEmpty()) return emptyList()
        //if there are many overlapped box with a sorting operation keep the better one
        val sorted = detections.sortedByDescending { it.confidence } //sort for box confidence O(N log N)
        val result = mutableListOf<Detection>()
        for (det in sorted) {
            var keep = true
            for (res in result) {
                //check only between box belong to the same class
                //iou evaluate the overlap
                if (det.classId == res.classId && iou(det.boundingBox, res.boundingBox) > iouThreshold) {
                    //if (iou(det.boundingBox, res.boundingBox) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) {
                result.add(det)
                if (result.size >= maxDetections) break
            }
        }
        return result
    }

    private fun iou(box1: RectF, box2: RectF): Float {
        val x1 = maxOf(box1.left, box2.left)
        val y1 = maxOf(box1.top, box2.top)
        val x2 = minOf(box1.right, box2.right)
        val y2 = minOf(box1.bottom, box2.bottom)
        if (x2 <= x1 || y2 <= y1) return 0f
        val interArea = (x2 - x1) * (y2 - y1)
        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        return interArea / (box1Area + box2Area - interArea)
    }
    fun close() {
        Log.d("YoloDetector", "Inizio rilascio risorse dell'interprete")
        try {
            // 1. Cancella i processi di salvataggio in corso
            saveScope.cancel()
            interpreter?.let {
                it.close()
                interpreter = null
            }
            gpuDelegate?.let {
                it.close()
                gpuDelegate = null
            }
            Log.d("YoloDetector", "Risorse rilasciate con successo")
        } catch (e: Exception) {
            Log.e("YoloDetector", "Errore durante la chiusura: ${e.message}")
        }
    }
    //from the documentation, open and load the model
    fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private fun saveCrop(fullBitmap: Bitmap, detection: Detection) {
        var croppedBitmap: Bitmap? = null
        try {
            val rect = detection.boundingBox
            val left = rect.left.toInt().coerceIn(0, fullBitmap.width - 1)
            val top = rect.top.toInt().coerceIn(0, fullBitmap.height - 1)
            val width = rect.width().toInt().coerceAtMost(fullBitmap.width - left)
            val height = rect.height().toInt().coerceAtMost(fullBitmap.height - top)

            if (width > 0 && height > 0) {
                croppedBitmap = Bitmap.createBitmap(fullBitmap, left, top, width, height)
                saveDetectionToDownloads(context, croppedBitmap, "${detection.classId}")
            }
        } catch (e: Exception) {
            Log.e("YoloDetector", "Save failed", e)
        } finally {
            croppedBitmap?.recycle()
        }
    }




}
////////////////////////////////////////////////////////////////////////////////////
fun saveDetectionToDownloads(context: Context, bitmap: Bitmap, className: String) {
    val label = when (className) {
        "2" -> "CAR"  // Nota: nei log precedenti l'ID dell'auto era 2
        "5" -> "BUS"
        else -> className
    }
    val filename = "Detection_${label}_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        // Specifichiamo la sottocartella in Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YoloDetections")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

    uri?.let {
        try {
            val outputStream: OutputStream? = resolver.openOutputStream(it)
            outputStream?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(it, contentValues, null, null)
            }
            Log.d("YoloDetector", "Immagine salvata in Downloads: $filename")
        } catch (e: Exception) {
            Log.e("YoloDetector", "Errore durante il salvataggio", e)
        }
    }
}

