package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

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
/////
//yolo predict model=yolov8n_saved_model/yolov8n_int8.tflite source=0 imgsz=640 int8 ////////////inference model test
//yolo export model=yolov8n.pt format=tflite int8 /////////////exporting command
///https://docs.ultralytics.com/it/datasets/detect/coco/ //////dataset train
// https://yolov8.com/
// https://colab.research.google.com/github/roboflow-ai/notebooks/blob/main/notebooks/train-yolov8-object-detection-on-custom-dataset.ipynb#scrollTo=jbVjEtPAkz3j
/*
* Weight Only / Hybrid Quantization: I pesi sono piccoli (INT8), ma l'interfaccia (input/output) rimane Float32 per mantenere la compatibilità e la precisione.
* D/YoloCheck: Tipo dati input: FLOAT32
D/YoloCheck: Shape input: [1, 640, 640, 3]
D/YoloCheck: Quantizzazione - Scale: 0.0, ZeroPoint: 0
 */
//https://ai.google.dev/edge/litert/android/index
data class Detection(val classId: Int, val confidence: Float, val boundingBox: RectF)
class YoloDetector(private val context: Context, modelFilename: String = "yolov8n_float32.tflite") { //by default use yolov8n_float32.tflite
    // Crea uno scope dedicato che non blocca il Main Thread o quello di YOLO
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var interpreter: Interpreter? = null //A Interpreter encapsulates a pre-trained TensorFlow Lite model, in which operations are executed for model inference
    private val inputSize = 640     // yolo need a 640x640 as input activation
    private val numClasses = 80
    private val numDetections = 8400 //yolo box
    private val maxDetections = 20 // max yolo items into a frame
    private val confThreshold = 0.7f //for object detection i want a model enough source
    private val iouThreshold = 0.5f // if this threshold is small i filter many overlap
    // --- VARIABILI AGGIUNTE PER RISOLVERE GLI ERRORI ---
    private var lastSaveTime: Long = 0
    private var log = true
    private val saveInterval: Long = 3000 // 3 secondi tra un salvataggio e l'altro
    // Buffer di output pre-allocato
    private var outputBuffer: Array<Array<FloatArray>> = Array(1) { Array(84) { FloatArray(numDetections) } }
    // ImageProcessor: gestisce ridimensionamento e normalizzazione in modo efficiente
    private val imageProcessor = ImageProcessor.Builder() //manage the input image trasformateion
        //bit map -> width: 540 , height: 960
        //but the yolo activation input is 640x640 so a reshape needed
        //tensor image -> width: 640 , height: 640
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR)) //540x960 (il tuo bitmap) a 640x640
        .add(NormalizeOp(0f, 255f)) // Normalizza i pixel da 0-255 a 0-1
        .build()
    private var gpuDelegate: GpuDelegate? = null
/*
    init { //class inizializatorn
        try {
            if (OpenCVLoader.initDebug()) {
                Log.d("OpenCV", "Libreria caricata correttamente!")
            } else {
                Log.e("OpenCV", "Errore nel caricamento della libreria.")
            }
            //https://ai.google.dev/edge/api/tflite/java/org/tensorflow/lite/gpu/GpuDelegateFactory.Options
            gpuDelegate = GpuDelegate(GpuDelegate.Options().apply {
                // Opzionale: permette calcoli a 16-bit per maggiore velocità
                setPrecisionLossAllowed(true)
                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            })

            val options2 = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
            }

            val modelBuffer = loadModelFile(context, modelFilename)
            //https://ai.google.dev/edge/api/tflite/java/org/tensorflow/lite/gpu/GpuDelegate   ->gpu delegation testing
            //https://blog.tensorflow.org/2019/01/tensorflow-lite-now-faster-with-mobile.html
            val options = Interpreter.Options().apply { setNumThreads(4) } //threads for inference parallels execution, better thoughput

            interpreter = Interpreter(modelBuffer, options2)
            interpreter?.allocateTensors()

            val inputTensor = interpreter?.getInputTensor(0)
            val inputDataType = inputTensor?.dataType()
            val inputShape = inputTensor?.shape()?.contentToString()

            val inputQuantizationScale = inputTensor?.quantizationParams()?.scale
            val inputQuantizationZeroPoint = inputTensor?.quantizationParams()?.zeroPoint

            Log.d("YoloDetector", "$modelFilename yolo detector loaded correctly | input data type : $inputDataType " +
                    "| input shape: $inputShape | input quantization scale $inputQuantizationScale and zero-point $inputQuantizationZeroPoint")

        } catch (e: Exception) {
            Log.e("YoloDetector", "Error in yolo initialization", e)
        }
    }
    */
    init {
        try {
        if (OpenCVLoader.initDebug()) {
            Log.d("YoloDetector", "OpenCV SDK loaded")
        } else {
            Log.e("YoloDetector", "OpenCV SDK loading problems")
        }
        var interpreterOptions = Interpreter.Options()
        try { // if there is no gpu available the catch part manage a cpu only setting for the inference component
            val modelBuffer = loadModelFile(context, modelFilename) // default yolov8n_float32.tflite model
            gpuDelegate = GpuDelegate(GpuDelegate.Options().apply {
                setPrecisionLossAllowed(true)
                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            })
            interpreterOptions.addDelegate(gpuDelegate)
            interpreter = Interpreter(modelBuffer, interpreterOptions)
            Log.d("YoloDetector", "using GPU for acceleration")
        } catch (e: Exception) {
            val modelFilename="yolov8n_int8.tflite" //default model, allways works
            val modelBuffer = loadModelFile(context, modelFilename) //with cpu only yolov8n_int8.tflite is better
            Log.w("YoloDetector", "No GPU available, fallback on CPU: ${e.message}")
            gpuDelegate?.close()
            gpuDelegate = null
            interpreterOptions = Interpreter.Options().apply {
                setNumThreads(4) // tread off for cpu optimization
            }
            interpreter = Interpreter(modelBuffer, interpreterOptions)
        }

        interpreter?.let { tfInterpreter -> //model tensor allocation and logging
            tfInterpreter.allocateTensors()
            val inputTensor = tfInterpreter.getInputTensor(0)
            val inputDataType = inputTensor.dataType()
            val inputShape = inputTensor.shape().contentToString()
            val quantParams = inputTensor.quantizationParams()
            Log.i("YoloDetector", """
                $modelFilename loaded !
                - Data Type: $inputDataType
                - Shape: $inputShape
                - Quantization Scale: ${quantParams.scale}
                - Zero Point: ${quantParams.zeroPoint}
                - Hardware: ${if (gpuDelegate != null) "GPU" else "CPU (4 threads)"}
            """.trimIndent())
        }
        } catch (e: Exception) {
            Log.e("YoloDetector", "Errore critico durante l'inizializzazione di YOLO", e)
        }
    }

    //main operation of yolo called by the model, passing the bitmap frame to detect, output  list of yolo Detected objects
    fun detect(bitmap: Bitmap): List<Detection> {
        val interp = interpreter ?: return emptyList()
        var tensorImage = TensorImage(DataType.FLOAT32) //the model is quant. with int8 bit for weight parameters but input keep as float32 bit
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)
        // save original image shape for remap
        val imgW = bitmap.width
        val imgH = bitmap.height
        try {
            // 2. Inferenza
            ///val startTime = System.nanoTime()
            interp.run(tensorImage.buffer, outputBuffer) // inference yolo operation
            //val endTime = System.nanoTime()
            //val inferenceTimeMs = (endTime - startTime) / 1_000_000.0 // converti in millisecondi
            //Log.d("YoloDetector", "Tempo di inferenza: $inferenceTimeMs ms")
            // 3. Post-processing (Estrazione e NMS)
            val rawDetections = extractDetections(outputBuffer[0], imgW, imgH) //because we have only one inference bach(image) ->outputBuffer[0]
            val finalDetections = nonMaxSuppression(rawDetections)
            Log.d("YoloDetector", "detected  ${finalDetections.size} items")
            val currentTime = System.currentTimeMillis()
            if (finalDetections.isNotEmpty() && (currentTime - lastSaveTime > saveInterval)) {
                lastSaveTime = currentTime
                /* thread is slow
                Thread {
                    for (det in finalDetections) {
                        saveCrop(bitmap, det)
                    }
                }.start()*/
                // Usa l'operatore elvis ?: per garantire un Config valido
                val bitmapToProcess = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                saveScope.launch {
                    for (det in finalDetections) {
                        saveCrop(bitmapToProcess, det)
                    }
                    // Fondamentale: libera la copia della bitmap madre dopo i ritagli
                    bitmapToProcess.recycle()
                }

            }
            return finalDetections
        } catch (e: Exception) {
            Log.e("YoloDetector", "Inference failed", e)
            return emptyList()
        }
    }

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
            // LIBERA LA MEMORIA del ritaglio immediatamente dopo il salvataggio
            croppedBitmap?.recycle()
        }
    }

    private fun extractDetections(output: Array<FloatArray>, imgW: Int, imgH: Int): List<Detection> {
        val detections = mutableListOf<Detection>()
        //input shape image 540x960, the yolo activation is 640x640 (the output is based on this scale)
        //i need scale factors to bring back the coordinate to the original ones
        val scaleX = imgW.toFloat() / inputSize
        val scaleY = imgH.toFloat() / inputSize
        //for each output box we get an items probability for each dataset COCO classes
        // the code is a O(N^2) very bad but the number are fixed and numClasses is very small -> 80
        for (i in 0 until numDetections) {  // yolo output box images
            var maxClassConf = -1f
            var classId = -1
            for (c in 0 until numClasses) { // for each blocks check the class probability distribution
                val score = output[c + 4][i]
                if (score > maxClassConf) {
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
                if (realW >= 100f && realH >= 100f) { //try to filter outliers items boxes
                    val realX = cx * inputSize * scaleX
                    val realY = cy * inputSize * scaleY
                    val left = (realX - realW / 2)
                    val top = (realY - realH / 2)
                    val right = (realX + realW / 2)
                    val bottom = (realY + realH / 2)
                    detections.add( Detection(classId, maxClassConf,
                        RectF(left.coerceAtLeast(0f), top.coerceAtLeast(0f), right.coerceAtMost(imgW.toFloat()), bottom.coerceAtMost(imgH.toFloat())))
                    )
                }
            }
        }
        return detections //output the list of detected items box with id, mx conf and original shape
    }

    // Le funzioni nonMaxSuppression, iou e loadModelFile rimangono identiche logicamente
    // ma beneficiano della maggiore pulizia del codice sopra.

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> { //algorithm for box detection filtering (box overlapping ecc)
        if (detections.isEmpty()) return emptyList()
        //if there are many overlapped box with a sorting operation keep the better one
        val sorted = detections.sortedByDescending { it.confidence } //sort for box confidence
        val result = mutableListOf<Detection>()
        for (det in sorted) {
            var keep = true
            for (res in result) {
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

    //from the documentation, open and load the model
    fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }
    fun close() {
        Log.e("YoloDetector", "start the resource interpreter release")
        saveScope.cancel() // eliminate eventually saving process
        interpreter?.close() //release interpreter resources
        interpreter = null
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


////////////////////////////////////////////////////////////////////////////////

/*
    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        //context.assets.openFd() system call for file loading
        // .use  exec the right AssetFileDescriptor closing at the end of the function
        context.assets.openFd(filename).use { assetFileDescriptor ->
            FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                return inputStream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
        }
    }*/
/*


* */