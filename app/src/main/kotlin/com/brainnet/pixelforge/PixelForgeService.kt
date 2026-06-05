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
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
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
    private var liteRtEngine: Engine? = null
    private var ktorServer: ApplicationEngine? = null

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
        try {
            // 1. Initialize LiteRT-LM engine with Tensor G5 NPU backend
            val modelPath = "${filesDir}/${MODEL_FILENAME}"
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.NPU(
                    nativeLibraryDir = applicationInfo.nativeLibraryDir
                )
            )
            liteRtEngine = Engine(engineConfig)
            liteRtEngine!!.initialize()
            Log.d(TAG, "LiteRT-LM engine initialized on NPU backend")

            // 2. Resolve Tailscale IP
            val tailscaleIp = resolveTailscaleIp()

            // 3. Start Ktor embedded server
            ktorServer = embeddedServer(CIO, host = "0.0.0.0", port = LITERT_PORT) {
                install(ContentNegotiation) {
                    json()
                }
                routing {
                    post("/v1/chat/completions") {
                        handleChatCompletion(call, liteRtEngine!!)
                    }
                    get("/health") {
                        call.respond(mapOf("status" to "ok"))
                    }
                }
            }.start(wait = false)

            Log.d(TAG, "Ktor server started on ${tailscaleIp}:${LITERT_PORT}")
            updateNotification("Server running on ${tailscaleIp}:${LITERT_PORT}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LiteRT server", e)
            updateNotification("Server failed to start. Check logs.")
            liteRtEngine = null
            ktorServer = null
        }
    }

    private fun stopLiteRTServer() {
        try {
            ktorServer?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
            ktorServer = null
            liteRtEngine?.close()
            liteRtEngine = null
            Log.d(TAG, "LiteRT-LM server stopped")
            updateNotification("PixelForge stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    // ---------- Chat completion handler ----------

    private suspend fun handleChatCompletion(
        call: ApplicationCall,
        engine: Engine
    ) {
        // Full OpenAI streaming implementation deferred to future task.
        // Currently parses the last user message and returns a non-streaming response.
        try {
            val requestBody = call.receive<JsonObject>()
            val messages = requestBody["messages"]?.jsonArray
            val lastUserMessage = messages
                ?.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                ?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: "Hello"

            val conversation = engine.createConversation()
            var responseText = ""
            conversation.sendMessage(
                Message.newBuilder()
                    .setRole(Message.Role.USER)
                    .addContent(Content.newBuilder().setText(lastUserMessage))
                    .build()
            ) { chunk ->
                responseText += chunk.text
            }

            // Return OpenAI-compatible response shape
            val response = buildJsonObject {
                put("id", "chatcmpl-pixelforge")
                put("object", "chat.completion")
                put("model", "gemma-4-e2b")
                putJsonArray("choices") {
                    addJsonObject {
                        put("index", 0)
                        putJsonObject("message") {
                            put("role", "assistant")
                            put("content", responseText)
                        }
                        put("finish_reason", "stop")
                    }
                }
            }
            call.respond(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat completion", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to e.message)
            )
        }
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
