package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

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
/////

//class YoloDetector(context: Context, modelFilename: String = "yolov8n_int8.tflite") {
class YoloDetector(private val context: Context, modelFilename: String = "yolov8n_int8.tflite") {
    // Crea uno scope dedicato che non blocca il Main Thread o quello di YOLO
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var interpreter: Interpreter? = null
    private val inputSize = 640     // yolo need a 640x640 as input activation
    private val numClasses = 80
    private val numDetections = 8400 //yolo box
    private val maxDetections = 10 // max yolo items into a frame
    private val confThreshold = 0.65f //for object detection i want a model enough source
    private val iouThreshold = 0.55f
    // --- VARIABILI AGGIUNTE PER RISOLVERE GLI ERRORI ---
    private var lastSaveTime: Long = 0
    private val saveInterval: Long = 3000 // 3 secondi tra un salvataggio e l'altro
    // Buffer di output pre-allocato
    private var outputBuffer: Array<Array<FloatArray>> = Array(1) { Array(84) { FloatArray(numDetections) } }

    // ImageProcessor: gestisce ridimensionamento e normalizzazione in modo efficiente
    private val imageProcessor = ImageProcessor.Builder() //manage the input image trasformateion
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f)) // Normalizza i pixel da 0-255 a 0-1
        .build()

    init { //class inizializatorn
        try {
            val modelBuffer = loadModelFile(context, modelFilename)
            val options = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(modelBuffer, options)
            Log.d("YoloDetector", "YOLO Model caricato correttamente")
        } catch (e: Exception) {
            Log.e("YoloDetector", "Errore inizializzazione", e)
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val interp = interpreter ?: return emptyList()

        // 1. Pre-processing rapido tramite libreria Support
        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // Memorizziamo le dimensioni originali per rimappare i box alla fine
        val imgW = bitmap.width
        val imgH = bitmap.height

        try {
            // 2. Inferenza
            val startTime = System.nanoTime()
            interp.run(tensorImage.buffer, outputBuffer)
            val endTime = System.nanoTime()
            val inferenceTimeMs = (endTime - startTime) / 1_000_000.0 // converti in millisecondi
            Log.d("YoloDetector", "Tempo di inferenza: $inferenceTimeMs ms")
            // 3. Post-processing (Estrazione e NMS)
            val rawDetections = extractDetections(outputBuffer[0], imgW, imgH)
            val finalDetections = nonMaxSuppression(rawDetections)
            Log.d("YoloDetector", "Rilevati ${finalDetections.size} oggetti")



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
                saveDetectionToDownloads(context, croppedBitmap, "item_id_${detection.classId}")
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

        // YOLOv8 produce output normalizzato (0-1) basato su inputSize (640)
        // Calcoliamo i fattori di conversione per tornare alle dimensioni reali (es. 540x960)
        val scaleX = imgW.toFloat() / inputSize
        val scaleY = imgH.toFloat() / inputSize

        for (i in 0 until numDetections) {
            // Calcolo veloce della confidenza massima tra le classi
            var maxClassConf = -1f
            var classId = -1
            for (c in 0 until numClasses) {
                val score = output[c + 4][i]
                if (score > maxClassConf) {
                    maxClassConf = score
                    classId = c
                }
            }
            if (maxClassConf > confThreshold) {
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]

                // 1. Calcola le dimensioni in pixel reali
                val realW = w * inputSize * scaleX
                val realH = h * inputSize * scaleY

                // 2. Filtra per dimensione minima (224x224)
                if (realW >= 150f && realH >= 150f) {

                    val realX = cx * inputSize * scaleX
                    val realY = cy * inputSize * scaleY

                    val left = (realX - realW / 2)
                    val top = (realY - realH / 2)
                    val right = (realX + realW / 2)
                    val bottom = (realY + realH / 2)

                    detections.add(
                        Detection(
                            classId,
                            maxClassConf,
                            RectF(
                                left.coerceAtLeast(0f),
                                top.coerceAtLeast(0f),
                                right.coerceAtMost(imgW.toFloat()),
                                bottom.coerceAtMost(imgH.toFloat())
                            )
                        )
                    )
                }
            }

        }
        return detections
    }

    // Le funzioni nonMaxSuppression, iou e loadModelFile rimangono identiche logicamente
    // ma beneficiano della maggiore pulizia del codice sopra.

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        for (det in sorted) {
            var keep = true
            for (res in result) {
                if (iou(det.boundingBox, res.boundingBox) > iouThreshold) {
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

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        context.assets.openFd(filename).use { assetFileDescriptor ->
            FileInputStream(assetFileDescriptor.fileDescriptor).use { inputStream ->
                return inputStream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.declaredLength
                )
            }
        }
    }

    fun close() {
        saveScope.cancel() // Ferma eventuali salvataggi in sospeso
        interpreter?.close()
        interpreter = null
    }
}
////////////////////////////////////////////////////////////////////////////////////
fun saveDetectionToDownloads(context: Context, bitmap: Bitmap, className: String) {
    val filename = "Detection_${className}_${System.currentTimeMillis()}.jpg"
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
data class Detection(val classId: Int, val confidence: Float, val boundingBox: RectF)


