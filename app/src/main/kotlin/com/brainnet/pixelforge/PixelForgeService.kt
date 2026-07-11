package com.brainnet.pixelforge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
        // Generic CPU/GPU model variant (no hardware suffix). The model
        // card's Android benchmark table runs BOTH the CPU and GPU backends
        // on this exact 2583 MB file, so it is the correct model for
        // Backend.CPU(). The hardware-suffixed variants (_Google_Tensor_G5,
        // _intel_*, _qualcomm_*) are NPU-precompiled for specific accelerators
        // and are NOT usable with the CPU/GPU backends. See task pixelforge-019.
        const val MODEL_FILENAME = "gemma-4-E2B-it_Google_Tensor_G5.litertlm"
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILENAME"
        private const val MIN_MODEL_SIZE = 100L * 1024 * 1024 // 100MB — guards against partial downloads
        private const val LITERT_PORT = 8080

        const val ACTION_LOG = "com.brainnet.pixelforge.LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"

        const val ACTION_BACKEND_INFO = "com.brainnet.pixelforge.BACKEND_INFO"
        const val EXTRA_BACKEND_NAME = "backend_name"
        const val ACTION_SERVER_READY = "com.brainnet.pixelforge.SERVER_READY"
    }

    private var sharedConversation: Any? = null  // Reused across all requests
    private val inferenceLock = Any()  // Serializes NPU inference calls — single session only

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var liteRtEngine: Engine? = null
    private var ktorServer: ApplicationEngine? = null
    private var downloadJob: kotlinx.coroutines.Job? = null

    // Persisted log on the app-specific external dir so logs survive a crash
    // and are readable without ADB (Files app or `adb pull`). No runtime
    // permission needed for getExternalFilesDir() on Android 10+.
    private val logFile: File by lazy {
        val dir = getExternalFilesDir(null) ?: filesDir
        File(dir, "pixelforge.log")
    }

    private fun writeToLogFile(line: String) {
        try {
            logFile.appendText("$line\n")
        } catch (e: Throwable) {
            // silent fail — never let the logger crash the service
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Rotate the log file on each fresh start so it does not grow forever
        try { logFile.delete() } catch (e: Throwable) {}
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("PixelForge starting…"))
        broadcastLog("PixelForge starting…")
        
        downloadJob?.cancel() // Cancel any in-flight download before starting a new one
        downloadJob = serviceScope.launch {
            downloadModel()
            // startLiteRTServer() is called by downloadModel() on success to avoid
            // the server starting when there's no model to load
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        downloadJob = null
        stopLiteRTServer()
        wakeLock?.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Model download ----------

    private suspend fun downloadModel() {
        broadcastLog("downloadModel: starting")

        val modelFile = File(filesDir, MODEL_FILENAME)

        // 1. Check if model already exists and is valid size (> 100MB guards partials)
        if (modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE) {
            broadcastLog("downloadModel: model already cached (${modelFile.length()} bytes) — skipping download")
            broadcastLog("Model ready. Starting server...")
            startLiteRTServer()
            return
        }

        // Stale partial — clean it up
        if (modelFile.exists()) {
            broadcastLog("downloadModel: removing stale partial (${modelFile.length()} bytes)")
            modelFile.delete()
        }

        // Check available storage space (4.5GB required: 3.95GB model + headroom)
        val minRequiredSpace = 4500L * 1024 * 1024 // 4.5GB in bytes
        val statFs = StatFs(filesDir.absolutePath)
        val availableSpace = statFs.availableBlocksLong * statFs.blockSizeLong

        if (availableSpace < minRequiredSpace) {
            val availableGb = String.format("%.2f", availableSpace / (1024.0 * 1024 * 1024))
            val requiredGb = String.format("%.2f", minRequiredSpace / (1024.0 * 1024 * 1024))
            Log.e(TAG, "downloadModel: insufficient storage (${availableGb}GB available, ${requiredGb}GB required)")
            broadcastLog("Insufficient storage: ${availableGb}GB available, ${requiredGb}GB required for download")
            return
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
                    throw IOException("HTTP ${response.code}: ${response.message}")
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
                                // Report every 5% to avoid spamming
                                if (percent - lastReportedPercent >= 5) {
                                    lastReportedPercent = percent
                                    broadcastLog("Downloading model: $percent%")
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
                broadcastLog("downloadModel: rename failed — leaving temp file in place")
                throw IOException("Failed to rename temp file to final")
            }

            broadcastLog("downloadModel: complete (${modelFile.length()} bytes)")

            // 5. On success
            broadcastLog("Model ready. Starting server...")
            startLiteRTServer()

        } catch (e: Exception) {
            Log.e(TAG, "downloadModel: failed", e)
            tempFile.delete() // best-effort cleanup
            // 6. User-visible failure
            broadcastLog("Download failed: ${e.message}. Tap to retry.")
        }
    }

    // ---------- LiteRT-LM server ----------

    private fun startLiteRTServer() {
        try {
            val modelPath = "${filesDir}/${MODEL_FILENAME}"

            // 1. Gate on AICore: check if device supports NPU via AICore package
            val hasAICore = try {
                packageManager.getPackageInfo("com.google.android.aicore", 0)
                broadcastLog("AICore detected — attempting NPU backend")
                true
            } catch (e: Exception) {
                broadcastLog("AICore not detected — skipping NPU backend")
                false
            }

            // 2. Load the dispatcher library
            val nativeLibraryDir = applicationInfo.nativeLibraryDir
            if (hasAICore) {
                try {
                    System.loadLibrary("LiteRtDispatch_GoogleTensor")
                    broadcastLog("LiteRtDispatch_GoogleTensor loaded from $nativeLibraryDir")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load dispatcher library", e)
                    broadcastLog("Warning: dispatcher library load failed — ${e.message}")
                }
            }

            // 3-4. Try NPU → GPU → CPU fallback chain with tracking
            var activeBackendName = "CPU"
            val engineConfig: EngineConfig = when {
                hasAICore -> {
                    try {
                        // Step 3: Construct NPU backend with nativeLibraryDir
                        val npuBackend = Backend.NPU(nativeLibraryDir = nativeLibraryDir)
                        // Step 4: Try NPU
                        activeBackendName = "NPU"
                        broadcastLog("Attempting NPU backend initialization...")
                        EngineConfig(
                            modelPath = modelPath,
                            backend = npuBackend,
                            visionBackend = null,
                            audioBackend = null
                        )
                    } catch (npuError: Throwable) {
                        Log.w(TAG, "NPU backend failed, trying GPU", npuError)
                        broadcastLog("NPU failed: ${npuError.message} — trying GPU")
                        try {
                            activeBackendName = "GPU"
                            broadcastLog("Attempting GPU backend initialization...")
                            EngineConfig(
                                modelPath = modelPath,
                                backend = Backend.GPU(),
                                visionBackend = null,
                                audioBackend = null
                            )
                        } catch (gpuError: Throwable) {
                            Log.w(TAG, "GPU backend failed, falling back to CPU", gpuError)
                            broadcastLog("GPU failed: ${gpuError.message} — falling back to CPU")
                            activeBackendName = "CPU"
                            EngineConfig(
                                modelPath = modelPath,
                                backend = Backend.CPU(),
                                visionBackend = null,
                                audioBackend = null
                            )
                        }
                    }
                }
                else -> {
                    // No AICore, try GPU then CPU
                    try {
                        activeBackendName = "GPU"
                        broadcastLog("Attempting GPU backend initialization...")
                        EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.GPU(),
                            visionBackend = null,
                            audioBackend = null
                        )
                    } catch (gpuError: Throwable) {
                        Log.w(TAG, "GPU backend failed, falling back to CPU", gpuError)
                        broadcastLog("GPU failed: ${gpuError.message} — falling back to CPU")
                        activeBackendName = "CPU"
                        EngineConfig(
                            modelPath = modelPath,
                            backend = Backend.CPU(),
                            visionBackend = null,
                            audioBackend = null
                        )
                    }
                }
            }

            // 5-6. Initialize engine and create conversation
            liteRtEngine = Engine(engineConfig)
            liteRtEngine!!.initialize()
            broadcastLog("LiteRT-LM engine initialized on $activeBackendName backend")


            broadcastBackendInfo(activeBackendName)

            // Resolve Tailscale IP
            val tailscaleIp = resolveTailscaleIp()

            // Start Ktor embedded server
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

            broadcastLog("Ktor server started on ${tailscaleIp}:${LITERT_PORT} (backend: $activeBackendName)")
            broadcastServerReady()

        } catch (e: Throwable) {
            // Catch Throwable, not just Exception: native NPU backend failures
            // (e.g. UnsatisfiedLinkError) and OOM are Errors, and would otherwise
            // kill the process silently with zero log output.
            Log.e(TAG, "Failed to start LiteRT server", e)
            broadcastLog("[ERROR] Server failed to start: ${e::class.simpleName}: ${e.message}")
            liteRtEngine = null
            ktorServer = null
            stopSelf()
        }
    }

    private fun stopLiteRTServer() {
        try {
            ktorServer?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
            ktorServer = null
            liteRtEngine?.close()
            liteRtEngine = null
            broadcastLog("LiteRT-LM server stopped")
            broadcastLog("PixelForge stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
            broadcastLog("Error stopping server: ${e.message}")
        }
    }

    // ---------- Chat completion handler ----------

    private suspend fun handleChatCompletion(
        call: ApplicationCall,
        engine: Engine
    ) {
        try {
            val requestBody = call.receive<JsonObject>()
            val messages = requestBody["messages"]?.jsonArray
            val lastUserMessage = messages
                ?.lastOrNull { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
                ?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: "Hello"
            val stream = requestBody["stream"]?.jsonPrimitive?.booleanOrNull ?: false

            if (stream) {
                handleStreamingChatCompletion(call, engine, lastUserMessage)
            } else {
                handleNonStreamingChatCompletion(call, engine, lastUserMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chat completion", e)
            broadcastLog("Chat completion error: ${e.message}")
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to e.message)
            )
        }
    }

    private suspend fun handleNonStreamingChatCompletion(
        call: ApplicationCall,
        engine: Engine,
        message: String
    ) {
        val conversation = synchronized(inferenceLock) {
            sharedConversation ?: engine.createConversation().also { sharedConversation = it }
        } as Any
        val llmResponse = (conversation as com.google.ai.edge.litertlm.Conversation).sendMessage(message)
        val responseText = llmResponse.contents.contents.joinToString("") {
            (it as? Content.Text)?.text ?: ""
        }

        // NOTE: LiteRT-LM 0.12.0 API does not expose token counting methods.
        // The Conversation and Response objects do not have methods to retrieve
        // prompt_tokens or completion_tokens. Real token counts would require
        // either: (a) a tokenizer library integration, or (b) access to
        // LiteRT-LM internal engine state (not currently exposed in the public API).
        // Usage field intentionally omitted to avoid fabricating token counts.
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
    }

    private suspend fun handleStreamingChatCompletion(
        call: ApplicationCall,
        engine: Engine,
        message: String
    ) {
        try {
            val conversation = synchronized(inferenceLock) {
                sharedConversation ?: engine.createConversation().also { sharedConversation = it }
            } as Any

            // Collect full response first (LiteRT-LM SDK is synchronous)
            // then stream it character-by-character to simulate SSE streaming
            val response = synchronized(inferenceLock) {
                (conversation as com.google.ai.edge.litertlm.Conversation).sendMessage(message)
            }
            val fullResponse = response.contents.contents.joinToString("") {
                (it as? Content.Text)?.text ?: ""
            }

            // Stream response as SSE
            call.respondBytesWriter(
                contentType = ContentType.Text.EventStream
            ) {
                // Split response into chunks (word-level for natural streaming feel)
                val words = fullResponse.split(" ")
                for ((index, word) in words.withIndex()) {
                    val chunk = if (index < words.size - 1) "$word " else word
                    val sseChunk = buildJsonObject {
                        put("id", "chatcmpl-pixelforge")
                        put("object", "chat.completion.chunk")
                        put("model", "gemma-4-e2b")
                        putJsonArray("choices") {
                            addJsonObject {
                                put("index", 0)
                                putJsonObject("delta") {
                                    put("role", "assistant")
                                    put("content", chunk)
                                }
                                put("finish_reason", JsonNull)
                            }
                        }
                    }
                    writeStringUtf8("data: ${sseChunk}\n\n")
                    flush()
                }

                // Final done chunk
                val finalChunk = buildJsonObject {
                    put("id", "chatcmpl-pixelforge")
                    put("object", "chat.completion.chunk")
                    put("model", "gemma-4-e2b")
                    putJsonArray("choices") {
                        addJsonObject {
                            put("index", 0)
                            putJsonObject("delta") {}
                            put("finish_reason", "stop")
                        }
                    }
                }
                writeStringUtf8("data: ${finalChunk}\n\n")
                writeStringUtf8("data: [DONE]\n\n")
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in streaming chat completion", e)
            // Can't send error SSE if headers already sent -- just log
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
                            broadcastLog("resolveTailscaleIp: found $host on $name")
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveTailscaleIp: failed to enumerate interfaces", e)
            broadcastLog("resolveTailscaleIp: failed to enumerate interfaces — ${e.message}")
        }

        broadcastLog("resolveTailscaleIp: no Tailscale IP found — falling back to 0.0.0.0")
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

    /**
     * Logs the message via Log.d AND broadcasts it to any registered
     * LocalBroadcastReceiver (e.g. MainActivity log view). Also updates
     * the foreground notification so the tray text stays in sync.
     */
    private fun broadcastLog(message: String) {
        Log.d(TAG, message)
        writeToLogFile(message)
        updateNotification(message)
        val intent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastBackendInfo(backendName: String) {
        val intent = Intent(ACTION_BACKEND_INFO).apply {
            putExtra(EXTRA_BACKEND_NAME, backendName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastServerReady() {
        val intent = Intent(ACTION_SERVER_READY)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
