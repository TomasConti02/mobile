package com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo

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
    private val motionRatioThreshold: Double = 0.06, //0.025
    private val historySize: Int = 6,
    private val warmupFrames: Int = 10,
    private val stableTimeMs: Long = 5_000L
) {
    enum class State { MOVING, STILL, STABLE }
    private var prevGray: Mat? = null
    private val history = ArrayDeque<Boolean>()
    private val currMat = Mat()
    private val grayMat = Mat()
    private val diffMat = Mat()
    private val blurMat = Mat()
    private val threshMat = Mat()
    // kernel pre allocated
    private val morphKernel: Mat = Mat.ones(3, 3, CvType.CV_8U)
    private var frameCount = 0
    private var currentState = State.MOVING
    private var stillStartTime: Long = 0L
    fun analyze(bitmap: Bitmap): State {
        //preprocessing phase
        Utils.bitmapToMat(bitmap, currMat) //openCV can not manage bitmap, a transformation is needed
        Imgproc.resize(currMat, currMat, Size(160.0, 120.0)) //reduce the size, trade off, less cpu (less pixel)
        Imgproc.cvtColor(currMat, grayMat, Imgproc.COLOR_RGBA2GRAY) // bring everything to gray (even less pixel)
        var isMoving = false
        prevGray?.let { prev -> //comparison between the current fream (grayMat) and the prevGray frame (prev)
            Core.absdiff(prev, grayMat, diffMat) //diff -> stable pixels became 0(black) others not black
            //blur is a denoise filter and create bigger areas
            Imgproc.blur(diffMat, blurMat, Size(5.0, 5.0)) //avoid noise by blurring
            Imgproc.threshold(
                blurMat,
                threshMat,
                55.0,
                255.0,
                Imgproc.THRESH_BINARY
            )
            Imgproc.morphologyEx(  //delete noise isolated pixels
                threshMat,
                threshMat,
                Imgproc.MORPH_OPEN, //OPENING => AoK=(A-K)+K => erosion and dilatation
                morphKernel
            )
            val motionPixels = Core.countNonZero(threshMat)
            val totalPixels = threshMat.rows() * threshMat.cols()
            val motionRatio = motionPixels.toDouble() / totalPixels
            isMoving = motionRatio > motionRatioThreshold

            if (motionRatio > motionRatioThreshold * 10) { //moving only if there is a big change
                Log.d("MotionDetector", "Massive motion detected - Resetting history to MOVING.")
                history.clear()
                repeat(historySize) { history.add(true) }
            }
        }
        frameCount++
        if (frameCount <= warmupFrames) { //Warmup skip first 10 frame for stability
            updatePrevGray()
            return currentState
        }
        updateHistory(isMoving)
        updatePrevGray() //swap between current frame and the prev frame

        return updateState()
    }

    private fun updatePrevGray() {
        if (prevGray == null) {
            prevGray = Mat(grayMat.size(), grayMat.type())
        }
        grayMat.copyTo(prevGray)
    }

    //updateHistory help to avoid spike in the MOVING - STILL detection operation
    private fun updateHistory(value: Boolean) { //Sliding Window using the circular buffer
        history.addLast(value) //append O(1)
        if (history.size > historySize) {
            history.removeFirst() //removed O(1)
        }
    }

    private fun updateState(): State { //manage the logic behind the state changes
        val movingCount = history.count { it }
        val newBaseState = when (currentState) {
            State.MOVING ->
                if (movingCount <= 2) State.STILL else State.MOVING
            State.STILL, State.STABLE ->
                if (movingCount >= 4) State.MOVING else State.STILL
        }
        if (newBaseState == State.STILL) {
            if (currentState != State.STILL && currentState != State.STABLE) {
                stillStartTime = System.currentTimeMillis()
            }
            val elapsed = System.currentTimeMillis() - stillStartTime
            currentState = if (elapsed >= stableTimeMs) {
                State.STABLE
            } else {
                State.STILL
            }
        } else {
            stillStartTime = 0L
            currentState = State.MOVING
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

        morphKernel.release()
    }
}
