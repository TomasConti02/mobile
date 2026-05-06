package com.meta.wearable.dat.externalsampleapps.cameraaccess.yolo
/*
In un'app wearable o mobile, inizializzare YOLO nel thread principale bloccherebbe l'interfaccia utente (UI Freeze). Questo pattern risolve tre problemi:
Non-Blocking: initAsync lancia il caricamento in background (Dispatchers.Default) e restituisce subito una "promessa" (Deferred).
Evita Inizializzazioni Multiple: Se chiami initAsync dieci volte mentre il modello sta ancora caricando, il codice non crea dieci modelli, ma restituisce a tutti lo stesso initJob.
Accesso Sospeso: Il metodo suspend fun get() permette di attendere il completamento solo se necessario, senza bloccare il thread.
* */
import android.content.Context
import kotlinx.coroutines.*
object YoloProvider {

    @Volatile
    private var instance: YoloDetector? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
