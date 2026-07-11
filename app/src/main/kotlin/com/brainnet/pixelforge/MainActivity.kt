package com.brainnet.pixelforge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_LOG = "com.brainnet.pixelforge.LOG"
        const val EXTRA_LOG_MESSAGE = "log_message"
        const val ACTION_BACKEND_INFO = "com.brainnet.pixelforge.BACKEND_INFO"
        const val ACTION_SERVER_READY = "com.brainnet.pixelforge.SERVER_READY"
        const val EXTRA_BACKEND_NAME = "backend_name"
    }

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var currentStatus: String = "Idle"
    private var currentBackend: String? = null

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(EXTRA_LOG_MESSAGE) ?: return
            logMessage(message)
        }
    }

    private val backendReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val backend = intent?.getStringExtra(EXTRA_BACKEND_NAME) ?: return
            currentBackend = backend
            updateStatusDisplay()
        }
    }

    private val serverReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            setStatus("Idle")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Apply WindowInsets padding to respect system status bar
        val rootView = findViewById<LinearLayout>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                v.paddingLeft,
                systemBars.top,
                v.paddingRight,
                v.paddingBottom
            )
            insets
        }

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        logScrollView = findViewById(R.id.logScrollView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener {
            val intent = Intent(this, PixelForgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            setStatus("Starting…")
            logMessage("Starting PixelForge service...")
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, PixelForgeService::class.java))
            setStatus("Idle")
            logMessage("Service stopped.")
        }
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(logReceiver, IntentFilter(ACTION_LOG))
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(backendReceiver, IntentFilter(ACTION_BACKEND_INFO))
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(serverReadyReceiver, IntentFilter(ACTION_SERVER_READY))
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(backendReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(serverReadyReceiver)
    }

    private fun setStatus(text: String) {
        currentStatus = text
        updateStatusDisplay()
    }

    private fun updateStatusDisplay() {
        val statusPart = "PixelForge — $currentStatus"
        val fullText = if (currentBackend != null) {
            val backendLabel = getBackendLabel(currentBackend!!)
            "$statusPart  $backendLabel"
        } else {
            statusPart
        }
        statusText.text = fullText
    }

    private fun getBackendLabel(backend: String): String {
        return when (backend) {
            "NPU" -> "🟢 Tensor G5 (NPU/TPU)"
            "GPU" -> "🔵 GPU"
            "CPU" -> "⚪ CPU"
            else -> ""
        }
    }

    private fun logMessage(message: String) {
        val timestamp = timeFormatter.format(Date())
        logText.append("[$timestamp] $message\n")
        logScrollView.post {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
