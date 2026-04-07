package com.example.motion

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max

class MotionDetector(

    private val motionRatioThreshold: Double = 0.025,
    private val historySize: Int = 6,
    private val warmupFrames: Int = 5,

    // 🔥 tempo per diventare STABLE
    private val stableTimeMs: Long = 5_000L

) {

    enum class State {
        MOVING,
        STILL,
        STABLE
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

    private var stillStartTime: Long = 0L

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

            // 🔥 anti-rumore
            if (motionRatio < 0.005) {
                motionRatio = 0.0
            }

            isMoving = motionRatio > motionRatioThreshold

            // 🔥 boost movimento forte
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

        return updateState()
    }

    private fun updateHistory(value: Boolean) {
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

