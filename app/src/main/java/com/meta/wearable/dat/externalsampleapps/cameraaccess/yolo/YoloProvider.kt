package com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo

import android.content.Context
import kotlinx.coroutines.*
/*
Try to implement the patter singleton factory, only one instance of the yolo detector into the heap
of the process. the load operation is very async and slow
*/
object YoloProvider {
    @Volatile
    private var instance: YoloDetector? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) //Dispatchers.IO thread pool for corutine good for loading operations
    private var initJob: Deferred<YoloDetector>? = null
    fun initAsync(context: Context): Deferred<YoloDetector> {
        instance?.let {
            return CompletableDeferred(it)
        }
        initJob?.let {
            return it
        }
        initJob = scope.async { //coruntine launch
            val detector = YoloDetector(context.applicationContext)
            instance = detector
            detector
        }
        return initJob!!
    }
    //need a asynch loading operation because don't want to block the main/ui thread
    suspend fun get(context: Context): YoloDetector { //corutine suspend itself until the resource is loaded
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
