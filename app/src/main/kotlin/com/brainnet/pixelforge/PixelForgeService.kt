package com.brainnet.pixelforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PixelForgeService : Service() {

    companion object {
        private const val TAG = "PixelForgeService"
        const val CHANNEL_ID = "pixelforge_status"
        const val NOTIFICATION_ID = 1
        const val MODEL_FILENAME = "gemma-4-E2B-it_Google_Tensor_G5.litertlm"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("PixelForge starting…"))
        serviceScope.launch {
            downloadModel()
            startLiteRTServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLiteRTServer()
        wakeLock?.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Model download ----------

    private suspend fun downloadModel() {
        Log.d(TAG, "downloadModel: stub — not implemented yet")
        // TODO: check if MODEL_FILENAME already exists in filesDir
        // TODO: stream download from HuggingFace with OkHttp, show progress notification
        updateNotification("Checking model…")
    }

    // ---------- LiteRT-LM server ----------

    private fun startLiteRTServer() {
        Log.d(TAG, "startLiteRTServer: stub — not implemented yet")
        // TODO: locate Tailscale mesh IP via ConnectivityManager / NetworkInterface
        // TODO: invoke LiteRT-LM SDK to load model and bind OpenAI-compatible HTTP on :8080
        updateNotification("Server running on :8080")
    }

    fun stopLiteRTServer() {
        Log.d(TAG, "stopLiteRTServer: stub — not implemented yet")
        // TODO: shut down LiteRT-LM runtime and release resources
    }

    // ---------- Helpers ----------

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelForge:WakeLock")
        wakeLock?.acquire()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PixelForge Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "LiteRT-LM inference server status"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PixelForge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
