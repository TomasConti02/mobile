package com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo

import android.content.Context
import kotlinx.coroutines.*

object YoloProvider {

    private var instance: YoloDetector? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initJob: Deferred<YoloDetector>? = null

    fun getAsync(context: Context): Deferred<YoloDetector> {
        val existing = instance
        if (existing != null) {
            return CompletableDeferred(existing)
        }
        if (initJob == null) {
            initJob = scope.async {
                val detector = YoloDetector(context.applicationContext)
                instance = detector
                detector
            }
        }
        return initJob!!
    }

    fun get(context: Context): YoloDetector {
        return instance ?: runBlocking {
            getAsync(context).await()
        }
    }

    fun close() {
        scope.coroutineContext.cancelChildren()
        instance?.close()
        instance = null
        initJob = null
    }
}