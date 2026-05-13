package com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo

import android.content.Context
import kotlinx.coroutines.*
object YoloProvider {

    @Volatile
    private var instance: YoloDetector? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initJob: Deferred<YoloDetector>? = null
    fun initAsync(context: Context): Deferred<YoloDetector> {
        instance?.let {
            return CompletableDeferred(it)
        }
        initJob?.let {
            return it
        }
        initJob = scope.async {
            val detector = YoloDetector(context.applicationContext)
            instance = detector
            detector
        }
        return initJob!!
    }

    suspend fun get(context: Context): YoloDetector { //suspend -> block and save corutine if the instance is not ready, do not block the thread
        return instance ?: initAsync(context).await()
    }
    //fun getOrNull(): YoloDetector? = instance
    fun close() {
        scope.coroutineContext.cancelChildren()
        instance?.close()
        instance = null
        initJob = null
    }
}
