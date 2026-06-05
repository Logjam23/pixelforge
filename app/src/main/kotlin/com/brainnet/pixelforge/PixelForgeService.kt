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
import java.net.Inet4Address
import java.net.NetworkInterface
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
        private const val LITERT_PORT = 8080
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var serverProcess: Process? = null

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
        Log.d(TAG, "startLiteRTServer: launching…")

        // 1. Verify model file exists
        val modelFile = File(filesDir, MODEL_FILENAME)
        if (!modelFile.exists()) {
            Log.e(TAG, "startLiteRTServer: model file not found at ${modelFile.absolutePath}")
            updateNotification("Model not found. Please restart.")
            return
        }

        // 2. Resolve Tailscale mesh IP
        val tailscaleIp = resolveTailscaleIp()
        Log.d(TAG, "startLiteRTServer: resolved bind address = $tailscaleIp")

        // 3. Locate the litert-lm binary
        //    On Android the LiteRT-LM SDK ships native libs into
        //    context.applicationInfo.nativeLibraryDir. The binary name below
        //    assumes the shared-object entry point published by the SDK AAR.
        //    TODO: verify exact binary name against the LiteRT-LM Android SDK
        //          AAR contents once the dependency is added to build.gradle.kts.
        val nativeLibDir = applicationInfo.nativeLibraryDir
        val binaryPath = "$nativeLibDir/liblitert_lm_main.so"
        val binary = File(binaryPath)
        if (!binary.exists() || !binary.canExecute()) {
            Log.w(TAG, "startLiteRTServer: binary not found or not executable at $binaryPath — attempting anyway")
        }

        // 4. Launch subprocess
        try {
            val cmd = listOf(
                binaryPath,
                "serve",
                "--model_path", modelFile.absolutePath,
                "--host", tailscaleIp,
                "--port", LITERT_PORT.toString()
            )

            Log.d(TAG, "startLiteRTServer: command = ${cmd.joinToString(" ")}")

            val process = ProcessBuilder(cmd)
                .directory(filesDir)
                .redirectErrorStream(true)
                .start()

            serverProcess = process

            // 5. Tail stdout in background coroutine
            serviceScope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lineSequence().forEach { line ->
                            Log.d(TAG, "litert-lm: $line")
                        }
                    }
                    // Process exited — reader closed naturally
                    val exitCode = try {
                        process.exitValue()
                    } catch (e: IllegalThreadStateException) {
                        -1 // still running — should not happen here but guard anyway
                    }
                    Log.w(TAG, "litert-lm process exited with code $exitCode")
                    updateNotification("Server stopped unexpectedly. Tap to restart.")
                } catch (e: Exception) {
                    Log.e(TAG, "startLiteRTServer: error reading subprocess stdout", e)
                }
            }

            Log.d(TAG, "startLiteRTServer: subprocess launched (pid=${process.pid()})")
            updateNotification("PixelForge running on $tailscaleIp:${LITERT_PORT}")

        } catch (e: Exception) {
            Log.e(TAG, "startLiteRTServer: failed to launch subprocess", e)
            serverProcess = null
            updateNotification("Server failed to start. Check logs.")
        }
    }

    private fun stopLiteRTServer() {
        val proc = serverProcess ?: return
        Log.d(TAG, "stopLiteRTServer: shutting down…")

        proc.destroy()
        val exited = proc.waitFor(5, TimeUnit.SECONDS)

        if (!exited) {
            Log.w(TAG, "stopLiteRTServer: process did not exit after 5s — force killing")
            proc.destroyForcibly()
        }

        serverProcess = null
        updateNotification("PixelForge stopped.")
        Log.d(TAG, "stopLiteRTServer: done")
    }

    // ---------- Network helpers ----------

    /**
     * Iterates network interfaces looking for a Tailscale tun interface
     * (named "tailscale0") or any interface with a 100.x.x.x address.
     * Falls back to "0.0.0.0" (bind all) if nothing found.
     */
    private fun resolveTailscaleIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name ?: continue

                // Tailscale tun interface on Android is typically named "tailscale0"
                val isTailscale = name == "tailscale0"

                val addresses = iface.interfaceAddresses ?: continue
                for (addr in addresses) {
                    val inet = addr.address
                    if (inet is Inet4Address) {
                        val host = inet.hostAddress ?: continue
                        if (isTailscale || host.startsWith("100.")) {
                            Log.d(TAG, "resolveTailscaleIp: found $host on $name")
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveTailscaleIp: failed to enumerate interfaces", e)
        }

        Log.d(TAG, "resolveTailscaleIp: no Tailscale IP found — falling back to 0.0.0.0")
        return "0.0.0.0"
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
