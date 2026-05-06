package com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo

object MotionDetectorFactory {

    @Volatile
    private var instance: MotionDetector? = null

    fun getInstance(
        motionRatioThreshold: Double = 0.025,
        historySize: Int = 6,
        warmupFrames: Int = 10,
        stableTimeMs: Long = 5_000L
    ): MotionDetector {
        return instance ?: MotionDetector(
            motionRatioThreshold,
            historySize,
            warmupFrames,
            stableTimeMs
        ).also { instance = it }
    }

    fun release() {
        synchronized(this) {
            instance?.release()
            instance = null
        }
    }
}
