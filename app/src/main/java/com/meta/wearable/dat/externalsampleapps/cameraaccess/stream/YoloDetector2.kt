
package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.*

//data class Detection(val classId: Int, val confidence: Float, val boundingBox: RectF)

class YoloDetector2(private val context: Context, modelFilename: String = "yolo26n_int8.tflite") {

    private var interpreter: Interpreter? = null
    private val inputSize = 640
    // Filtri più aggressivi
    private var confThreshold = 0.5f          // solo rilevazioni molto sicure
    private val minBoxSize = 80f               // oggetti piccoli (<150px) scartati
    private val maxDetectionsPerFrame = 10       // massimo 5 oggetti per frame
    private val saveInterval = 3000L
    private var lastSaveTime = 0L
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Output buffer per il modello [1, 300, 6]
    private var outputBuffer: Array<Array<FloatArray>> = Array(1) { Array(300) { FloatArray(6) } }

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
        .add(NormalizeOp(0f, 255f))
        .build()

    init {
        try {
            val modelBuffer = loadModelFile(context, modelFilename)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(modelBuffer, options)
            interpreter?.allocateTensors()

            val outputTensor = interpreter?.getOutputTensor(0)
            Log.d("YoloDetector", "Output shape: ${outputTensor?.shape()?.contentToString()}")
            Log.d("YoloDetector", "Config: conf=$confThreshold, minBox=$minBoxSize, maxDet=$maxDetectionsPerFrame")
        } catch (e: Exception) {
            Log.e("YoloDetector", "Init failed", e)
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val interp = interpreter ?: return emptyList()

        // Prepara input
        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        val imgW = bitmap.width
        val imgH = bitmap.height

        try {
            // Inferenza
            interp.run(tensorImage.buffer, outputBuffer)

            // Parsing output [1, 300, 6]
            val detections = mutableListOf<Detection>()
            val rawOutput = outputBuffer[0]

            // DEBUG: stampa i primi 10 output grezzi (utile per verificare l'ordine dei campi)
            for (i in 0 until minOf(10, rawOutput.size)) {
                val d = rawOutput[i]
                Log.d("YoloDetector", "raw[$i]: ${d.joinToString(", ")}")
            }

            for (i in rawOutput.indices) {
                val detection = rawOutput[i]
                // Supponiamo [x1, y1, x2, y2, class_id, confidence]
                // Se i log mostrano confidence al 5° campo (indice 4), scambia le righe.
                val x1 = detection[0].coerceIn(0f, 1f)
                val y1 = detection[1].coerceIn(0f, 1f)
                val x2 = detection[2].coerceIn(0f, 1f)
                val y2 = detection[3].coerceIn(0f, 1f)
                val classId = detection[4].toInt()
                val confidence = detection[5]

                // Filtro per classe: solo auto (2) e bus (5)
                if (classId != 2 && classId != 5) continue

                if (confidence < confThreshold) continue

                // Coordinate assolute
                val left = x1 * imgW
                val top = y1 * imgH
                val right = x2 * imgW
                val bottom = y2 * imgH

                val width = right - left
                val height = bottom - top

                // Filtro dimensione minima
                if (width < minBoxSize || height < minBoxSize) continue

                // Filtro rapporto di forma (evita box eccessivamente allungati)
                val aspectRatio = width / height
                if (aspectRatio < 0.3f || aspectRatio > 3.0f) continue

                detections.add(Detection(classId, confidence, RectF(left, top, right, bottom)))
            }

            // NMS con IoU stretta (0.4) e limite massimo
            val finalDetections = nonMaxSuppression(detections, iouThreshold = 0.5f)
                .take(maxDetectionsPerFrame)

            Log.d("YoloDetector", "Raw: ${detections.size} -> After NMS: ${finalDetections.size}")

            // Salvataggio asincrono (opzionale – rimuovi se non necessario)
            if (finalDetections.isNotEmpty()) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSaveTime > saveInterval) {
                    lastSaveTime = currentTime
                    val bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    saveScope.launch {
                        for (det in finalDetections) {
                            saveCrop(bitmapCopy, det)
                        }
                        bitmapCopy.recycle()
                    }
                }
            }

            return finalDetections
        } catch (e: Exception) {
            Log.e("YoloDetector", "Inference failed", e)
            return emptyList()
        }
    }

    // ------------------------------------------------------------
    //  NMS utilities
    // ------------------------------------------------------------
    private fun nonMaxSuppression(detections: List<Detection>, iouThreshold: Float): List<Detection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.confidence }
        val result = mutableListOf<Detection>()
        for (det in sorted) {
            var keep = true
            for (res in result) {
                if (det.classId == res.classId && iou(det.boundingBox, res.boundingBox) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) result.add(det)
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

    // ------------------------------------------------------------
    //  Salvataggio e gestione file
    // ------------------------------------------------------------
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
                saveDetectionToDownloads(context, croppedBitmap, detection.classId.toString())
            }
        } catch (e: Exception) {
            Log.e("YoloDetector", "Save failed", e)
        } finally {
            croppedBitmap?.recycle()
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    fun close() {
        saveScope.cancel()
        interpreter?.close()
        interpreter = null
    }
}


