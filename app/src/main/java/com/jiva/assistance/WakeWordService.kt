package com.jiva.assistance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.picovoice.porcupine.PorcupineManager
import com.jiva.assistance.data.ApiKeyStore
import com.jiva.assistance.domain.WakeHandoffGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WakeWordService : Service() {
    private val store by lazy { ApiKeyStore(this) }
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val managerMutex = Mutex()
    private var porcupineManager: PorcupineManager? = null
    private var managerStarted = false
    // Picovoice may deliver overlapping detections before stop() completes.
    private val wakeHandoff = WakeHandoffGate()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                wakeHandoff.disarm()
                store.setWakeServiceEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (!store.isWakeServiceEnabled()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithNotification("Listening for Jiva or Giva")
                serviceScope.launch { startWakeWordManager() }
                return START_STICKY
            }
            ACTION_PAUSE -> {
                serviceScope.launch {
                    try {
                        stopWakeWordManager()
                        updateNotification("Microphone paused while Jiva is active")
                        sendBroadcast(Intent(ACTION_PAUSED).setPackage(packageName))
                    } catch (error: Exception) {
                        sendBroadcast(
                            Intent(ACTION_ERROR)
                                .setPackage(packageName)
                                .putExtra(EXTRA_ERROR, error.message ?: "Could not pause wake-word listening."),
                        )
                    }
                }
                return START_STICKY
            }
        }
        if (!store.isWakeServiceEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithNotification("Listening for Jiva or Giva")
        serviceScope.launch { startWakeWordManager() }
        return START_STICKY
    }

    private suspend fun startWakeWordManager() = managerMutex.withLock {
        try {
            val accessKey = store.readPicovoiceAccessKey()
            check(accessKey.isNotBlank()) { "Add a Picovoice AccessKey in Wake word settings." }
            val models = store.readWakeWordModels()
            check(models.size == 2) { "Import both Jiva and Giva .ppn wake-word models." }
            val manager = porcupineManager ?: PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPaths(models.toTypedArray())
                .setSensitivities(floatArrayOf(0.55f, 0.55f))
                .build(this) { keywordIndex -> onWakeWordDetected(keywordIndex) }
                .also { porcupineManager = it }
            if (!managerStarted) {
                // Re-arm only after the microphone successfully starts again.
                manager.start()
                managerStarted = true
                wakeHandoff.arm()
            }
            updateNotification("Listening for Jiva or Giva")
        } catch (error: Exception) {
            store.setWakeServiceEnabled(false)
            sendBroadcast(
                Intent(ACTION_STOPPED)
                    .setPackage(packageName),
            )
            sendBroadcast(
                Intent(ACTION_ERROR)
                    .setPackage(packageName)
                    .putExtra(EXTRA_ERROR, error.message ?: "Wake-word listening could not start."),
            )
            updateNotification(error.message ?: "Wake-word listening could not start.")
            stopSelf()
        }
    }

    private suspend fun stopWakeWordManager() = managerMutex.withLock {
        if (managerStarted) {
            porcupineManager?.stop()
            managerStarted = false
        }
    }

    private fun onWakeWordDetected(keywordIndex: Int) {
        if (!wakeHandoff.claim()) return
        serviceScope.launch {
            try {
                stopWakeWordManager()
                sendBroadcast(
                    Intent(ACTION_WAKE_WORD)
                        .setPackage(packageName)
                        .putExtra(EXTRA_KEYWORD_INDEX, keywordIndex),
                )
                updateNotification("Wake word heard · tap to return to Jiva")
            } catch (error: Exception) {
                sendBroadcast(
                    Intent(ACTION_ERROR)
                        .setPackage(packageName)
                        .putExtra(EXTRA_ERROR, error.message ?: "Could not pause wake-word listening."),
                )
                // Do not re-arm until a subsequent successful ACTION_RESUME.
            }
        }
    }

    private fun startForegroundWithNotification(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(message),
        )
    }

    private fun buildNotification(message: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jiva wake word is active")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Jiva wake-word listening",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    override fun onDestroy() {
        store.setWakeServiceEnabled(false)
        serviceScope.launch {
            try {
                managerMutex.withLock {
                    try {
                        if (managerStarted) porcupineManager?.stop()
                    } finally {
                        managerStarted = false
                        porcupineManager?.delete()
                        porcupineManager = null
                    }
                }
            } finally {
                // A destroyed service must not retain an active coroutine scope.
                serviceJob.cancel()
            }
        }
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_WAKE_WORD = "com.jiva.assistance.WAKE_WORD"
        const val ACTION_ERROR = "com.jiva.assistance.WAKE_WORD_ERROR"
        const val ACTION_RESUME = "com.jiva.assistance.RESUME_WAKE_WORD"
        const val ACTION_PAUSE = "com.jiva.assistance.PAUSE_WAKE_WORD"
        const val ACTION_PAUSED = "com.jiva.assistance.WAKE_WORD_PAUSED"
        const val ACTION_STOP = "com.jiva.assistance.STOP_WAKE_WORD"
        const val ACTION_STOPPED = "com.jiva.assistance.WAKE_WORD_STOPPED"
        const val EXTRA_KEYWORD_INDEX = "keyword_index"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "jiva_wake_word"
        private const val NOTIFICATION_ID = 721
    }
}
