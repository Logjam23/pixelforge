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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PixelForgeService : Service() {

    companion object {
        private const val TAG = "PixelForgeService"
        const val CHANNEL_ID = "pixelforge_status"
        const val NOTIFICATION_ID = 1
        const val MODEL_FILENAME = "gemma-4-E2B-it_Google_Tensor_G5.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILENAME"
        private const val MIN_MODEL_SIZE = 100L * 1024 * 1024 // 100MB — guards against partial downloads
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
            // startLiteRTServer() is called by downloadModel() on success to avoid
            // the server starting when there's no model to load
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
        Log.d(TAG, "downloadModel: starting")

        val modelFile = File(filesDir, MODEL_FILENAME)

        // 1. Check if model already exists and is valid size (> 100MB guards partials)
        if (modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE) {
            Log.d(TAG, "downloadModel: model already cached (${modelFile.length()} bytes) — skipping download")
            updateNotification("Model ready. Starting server...")
            startLiteRTServer()
            return
        }

        // Stale partial — clean it up
        if (modelFile.exists()) {
            Log.d(TAG, "downloadModel: removing stale partial (${modelFile.length()} bytes)")
            modelFile.delete()
        }

        val tempFile = File(filesDir, "$MODEL_FILENAME.tmp")

        try {
            withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.MINUTES)
                    .writeTimeout(5, TimeUnit.MINUTES)
                    .build()

                val request = Request.Builder().url(MODEL_URL).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code()}: ${response.message()}")
                }

                val body = response.body ?: throw IOException("Empty response body")
                val contentLength = body.contentLength()

                body.byteStream().use { input ->
                    tempFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(8192)
                        var totalRead = 0L
                        var lastReportedPercent = -1

                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            if (contentLength > 0) {
                                val percent = ((totalRead * 100) / contentLength).toInt()
                                // Report every 5% to avoid spamming NotificationManager
                                if (percent - lastReportedPercent >= 5) {
                                    lastReportedPercent = percent
                                    updateNotification("Downloading model: $percent%")
                                }
                            }
                        }
                    }
                }

                response.close()
            }

            // 3. Atomically rename temp → final only on successful download
            if (!tempFile.renameTo(modelFile)) {
                Log.w(TAG, "downloadModel: rename failed — leaving temp file in place")
                throw IOException("Failed to rename temp file to final")
            }

            Log.d(TAG, "downloadModel: complete (${modelFile.length()} bytes)")

            // 5. On success
            updateNotification("Model ready. Starting server...")
            startLiteRTServer()

        } catch (e: Exception) {
            Log.e(TAG, "downloadModel: failed", e)
            tempFile.delete() // best-effort cleanup
            // 6. User-visible failure
            updateNotification("Download failed. Tap to retry.")
        }
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
