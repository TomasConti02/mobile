package com.example.motion

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import org.opencv.core.Core
/*
this class receive a sequence of bitmap frame from the controller if the stream and define
if the scene is moving, still or stable after a stableTimeMs of still state.
*/
class MotionDetector(
    private val motionRatioThreshold: Double = 0.025, //2.5% motion pixel between bitmaps images of a moving scene
    private val historySize: Int = 6, // bitmap frame cached and considered in time for the state
    private val warmupFrames: Int = 5, //skipped frame at the start of the streaming for stabilization
    private val stableTimeMs: Long = 5_000L //still time before became stable
    ) {
    enum class State {  MOVING, STILL, STABLE }
    private var prevGray: Mat? = null
    private val history = ArrayDeque<Boolean>() //history queue state
    private val currMat = Mat()
    private val grayMat = Mat()
    private val diffMat = Mat()
    private val blurMat = Mat()
    private val threshMat = Mat()
    private val morphKernel: Mat = Mat.ones(3, 3, CvType.CV_8U)
    /*
private val grayMat = UMat() //for gpu
private val diffMat = UMat()
* */
    private var frameCount = 0
    private var currentState = State.MOVING
    private var stillStartTime: Long = 0L
/*
    fun analyze(bitmap: Bitmap): State {
        val bitmap = Bitmap.createScaledBitmap(bitmap, 160, 120, false)
        Utils.bitmapToMat(bitmap, currMat) //convert the bitmap into a opencv pixel matrix
        Imgproc.cvtColor(currMat, grayMat, Imgproc.COLOR_BGR2GRAY) //convert the RGB matrix into a gray (less pixel, easy to execute)
        var isMoving = false
        var motionRatio = 0.0
        prevGray?.let { prev -> //lamba function operation if and only if prevgray is not null
            Core.absdiff(prev, grayMat, diffMat) //absolute difference between gray pixels images values
            Imgproc.GaussianBlur(diffMat, blurMat, Size(9.0, 9.0), 0.0) //exec GB filter in order to reduce the noise for the absolute diff
            Imgproc.threshold(blurMat, threshMat, 40.0, 255.0, Imgproc.THRESH_BINARY) //pixels with a abs diff of >40 became 255(moving) otherwise 0 (still)
            Imgproc.morphologyEx(threshMat, threshMat, Imgproc.MORPH_OPEN, Mat.ones(3, 3, CvType.CV_8U)) //opening, reduce the noise outliers
            val motionPixels = Core.countNonZero(threshMat) //count white pixels
            val totalPixels = max(1, threshMat.rows() * threshMat.cols())
            motionRatio = motionPixels.toDouble() / totalPixels //motion area
            if (motionRatio < 0.005) { //filtering motio ratio outliers
                motionRatio = 0.0
            }
            isMoving = motionRatio > motionRatioThreshold //define is the state is in moving based on the class threshold
            if (motionRatio > motionRatioThreshold * 5) { //change very fast the state with out the need of recreate the entire history
                history.clear()
                repeat(historySize) { history.add(true) }
            }
            Log.d("Motion", "ratio=$motionRatio moving=$isMoving")
        }
        frameCount++
        if (frameCount <= warmupFrames) {  isMoving = false  } //set at the begging for stabilization
        updateHistory(isMoving)
        prevGray?.release() //clean the memory of the gray images
        prevGray = grayMat.clone()
        return updateState()
    }

 */
fun analyze(bitmap: Bitmap): State {

    // ❌ evita shadowing + reuse pattern più pulito
    val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 160, 120, false)

    // conversione bitmap → Mat (ok, inevitabile)
    Utils.bitmapToMat(scaledBitmap, currMat)

    // resize IN OpenCV (più veloce e senza GC)
    Imgproc.resize(currMat, currMat, Size(160.0, 120.0))

    Imgproc.cvtColor(currMat, grayMat, Imgproc.COLOR_BGR2GRAY)

    var isMoving = false

    prevGray?.let { prev ->

        // diff
        Core.absdiff(prev, grayMat, diffMat)

        // blur (leggermente più piccolo kernel = meno CPU)
        Imgproc.GaussianBlur(diffMat, blurMat, Size(7.0, 7.0), 0.0)

        // threshold
        Imgproc.threshold(
            blurMat,
            threshMat,
            40.0,
            255.0,
            Imgproc.THRESH_BINARY
        )

        // ⚠️ IMPORTANTISSIMO: NON creare Mat.ones ogni frame
        // meglio cache statico
        Imgproc.morphologyEx(
            threshMat,
            threshMat,
            Imgproc.MORPH_OPEN,
            morphKernel
        )

        val motionPixels = Core.countNonZero(threshMat)

        val totalPixels = threshMat.rows() * threshMat.cols()

        val motionRatio = motionPixels.toDouble() / totalPixels

        isMoving = motionRatio > motionRatioThreshold

        if (motionRatio > motionRatioThreshold * 5) {
            history.clear()
            repeat(historySize) { history.add(true) }
        }

        Log.d("Motion", "ratio=$motionRatio moving=$isMoving")
    }

    frameCount++

    if (frameCount <= warmupFrames) {
        isMoving = false
    }

    updateHistory(isMoving)

    // cleanup corretto
    prevGray?.release()
    prevGray = grayMat.clone()

    return updateState()
}
    private fun updateHistory(value: Boolean) { //keeping a history of historySize true->moving of false->still result of the frame bitmap state
        history.addLast(value)
        if (history.size > historySize) {
            history.removeFirst()
        }
    }
    private fun updateState(): State {
        val movingCount = history.count { it }
        val newBaseState = when (currentState) {
            State.MOVING -> {
                if (movingCount <= 2) State.STILL else State.MOVING
            }
            State.STILL, State.STABLE -> {
                if (movingCount >= 4) State.MOVING else State.STILL
            }
        }
        if (newBaseState == State.STILL) {
            if (currentState != State.STILL && currentState != State.STABLE) {
                // entrato in STILL → start timer
                stillStartTime = System.currentTimeMillis()
            }
            val elapsed = System.currentTimeMillis() - stillStartTime
            currentState = if (elapsed >= stableTimeMs) {
                State.STABLE
            } else {
                State.STILL
            }
        } else {
            // reset se torna movimento
            stillStartTime = 0L
            currentState = State.MOVING
        }
        Log.d("Motion", "state=$currentState stillTime=${System.currentTimeMillis() - stillStartTime}")
        return currentState
    }
    fun release() {
        prevGray?.release()
        currMat.release()
        grayMat.release()
        diffMat.release()
        blurMat.release()
        threshMat.release()
    }
}
/*
package com.example.motion

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max

class MotionDetector(

    // leggermente più alta → meno falsi positivi
    private val motionRatioThreshold: Double = 0.025,

    private val historySize: Int = 6,

    private val warmupFrames: Int = 5

) {

    enum class State {
        MOVING,
        STILL
    }

    private var prevGray: Mat? = null
    private val history = ArrayDeque<Boolean>()

    private val currMat = Mat()
    private val grayMat = Mat()
    private val diffMat = Mat()
    private val blurMat = Mat()
    private val threshMat = Mat()

    private var frameCount = 0
    private var currentState = State.STILL

    fun analyze(bitmap: Bitmap): State {

        Utils.bitmapToMat(bitmap, currMat)
        Imgproc.cvtColor(currMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        var isMoving = false
        var motionRatio = 0.0

        prevGray?.let { prev ->

            Core.absdiff(prev, grayMat, diffMat)

            Imgproc.GaussianBlur(diffMat, blurMat, Size(9.0, 9.0), 0.0)

            Imgproc.threshold(blurMat, threshMat, 40.0, 255.0, Imgproc.THRESH_BINARY)

            Imgproc.morphologyEx(
                threshMat,
                threshMat,
                Imgproc.MORPH_OPEN,
                Mat.ones(3, 3, CvType.CV_8U)
            )

            val motionPixels = Core.countNonZero(threshMat)
            val totalPixels = max(1, threshMat.rows() * threshMat.cols())
            motionRatio = motionPixels.toDouble() / totalPixels

            // 🔥 filtro anti-rumore (fondamentale)
            if (motionRatio < 0.005) {
                motionRatio = 0.0
            }

            isMoving = motionRatio > motionRatioThreshold

            // 🔥 boost solo per movimento evidente
            if (motionRatio > motionRatioThreshold * 5) {
                history.clear()
                repeat(historySize) { history.add(true) }
            }

            Log.d("Motion", "ratio=$motionRatio moving=$isMoving")
        }

        frameCount++

        if (frameCount <= warmupFrames) {
            isMoving = false
        }

        updateHistory(isMoving)

        prevGray?.release()
        prevGray = grayMat.clone()

        return getBalancedState()
    }

    private fun updateHistory(value: Boolean) {
        history.addLast(value)
        if (history.size > historySize) {
            history.removeFirst()
        }
    }

    // 🔥 LOGICA BILANCIATA
    private fun getBalancedState(): State {
        val movingCount = history.count { it }

        currentState = when (currentState) {

            State.STILL -> {
                // entra solo se movimento consistente
                if (movingCount >= 4) State.MOVING else State.STILL
            }

            State.MOVING -> {
                // esce più facilmente → torna a STILL prima
                if (movingCount <= 2) State.STILL else State.MOVING
            }
        }

        return currentState
    }

    fun release() {
        prevGray?.release()
        currMat.release()
        grayMat.release()
        diffMat.release()
        blurMat.release()
        threshMat.release()
    }
}
*/
/*package com.example.motion

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import android.util.Log

class MotionDetector(
    // percentuale pixel per movimento (0.0 - 1.0)
    private val motionRatioThreshold: Double = 0.02, // 2%
    // stabilizzazione temporale
    private val historySize: Int = 10,
    private val motionFramesRequired: Int = 7,
    // warmup iniziale (frame ignorati)
    private val warmupFrames: Int = 5
) {

    enum class State {
        MOVING,
        STILL
    }

    private var prevGray: Mat? = null
    private val history = ArrayDeque<Boolean>()

    private val currMat = Mat()
    private val grayMat = Mat()
    private val diffMat = Mat()
    private val blurMat = Mat()
    private val threshMat = Mat()

    private var frameCount = 0

    fun analyze(bitmap: Bitmap): State {

        // 1. Bitmap → Mat
        Utils.bitmapToMat(bitmap, currMat)

        // 2. Grayscale
        Imgproc.cvtColor(currMat, grayMat, Imgproc.COLOR_BGR2GRAY)

        var isMoving = false

        prevGray?.let { prev ->

            // 3. Differenza tra frame
            Core.absdiff(prev, grayMat, diffMat)

            // 4. Blur forte (riduce rumore)
            Imgproc.GaussianBlur(diffMat, blurMat, Size(9.0, 9.0), 0.0)

            // 5. Threshold più alto
            Imgproc.threshold(blurMat, threshMat, 40.0, 255.0, Imgproc.THRESH_BINARY)

            // 6. Rimozione rumore (morfologia)
            Imgproc.morphologyEx(
                threshMat,
                threshMat,
                Imgproc.MORPH_OPEN,
                Mat.ones(3, 3, CvType.CV_8U)
            )

            // 7. Calcolo percentuale movimento
            val motionPixels = Core.countNonZero(threshMat)
            val totalPixels = max(1, threshMat.rows() * threshMat.cols())
            val motionRatio = motionPixels.toDouble() / totalPixels

            isMoving = motionRatio > motionRatioThreshold

            Log.d("Motion", "ratio: $motionRatio, moving: $isMoving")
        }

        frameCount++

        // 8. Warmup (ignora primi frame)
        if (frameCount <= warmupFrames) {
            isMoving = false
        }

        // 9. Aggiorna history
        updateHistory(isMoving)

        // 10. Salva frame corrente
        prevGray?.release()
        prevGray = grayMat.clone()

        return getStableState()
    }

    private fun updateHistory(value: Boolean) {
        history.addLast(value)
        if (history.size > historySize) {
            history.removeFirst()
        }
    }

    private fun getStableState(): State {
        val movingCount = history.count { it }

        return if (movingCount >= motionFramesRequired) {
            State.MOVING
        } else {
            State.STILL
        }
    }

    fun release() {
        prevGray?.release()
        currMat.release()
        grayMat.release()
        diffMat.release()
        blurMat.release()
        threshMat.release()
    }
}
*/

