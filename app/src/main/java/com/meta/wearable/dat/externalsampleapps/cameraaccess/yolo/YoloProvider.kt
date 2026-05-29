package com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo

import android.content.Context
import kotlinx.coroutines.*
/*
Try to implement the patter singleton factory,
only one instance of the yolo detector into the gpu.
The load operation is blocking and synch, better asynch
*/
object YoloProvider { //Singleton
    @Volatile     //All the thread ( UI thread) of the process can access to the variabile as soon as available
    private var instance: YoloDetector? = null
    private val scope = CoroutineScope(Dispatchers.IO) //Dispatchers.IO thread pool for corutine good for loading operations
    //private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initJob: Deferred<YoloDetector>? = null //waiting ticket of the asynch corutine
    fun initAsync(context: Context): Deferred<YoloDetector> {

        instance?.let { //object is already available, return it
            return CompletableDeferred(it)
        }

        initJob?.let { //if the object already exist do not create another
            return it //get out the waiting reference
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
