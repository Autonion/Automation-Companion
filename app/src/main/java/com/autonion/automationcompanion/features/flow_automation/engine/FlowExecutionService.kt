package com.autonion.automationcompanion.features.flow_automation.engine

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.flow_automation.data.FlowRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "FlowExecutionService"
private const val CHANNEL_ID = "flow_execution_channel"
private const val NOTIFICATION_ID = 6001
private val DBG_CATEGORY = LogCategory.FLOW_BUILDER

/**
 * Foreground service that hosts the FlowExecutionEngine.
 *
 * Shows a persistent notification with the active node name,
 * and a floating overlay with a panic button to stop execution.
 *
 * Supports MediaProjection for visual trigger and screen ML nodes.
 * Pass EXTRA_RESULT_CODE + EXTRA_RESULT_DATA from a MediaProjection
 * consent intent to enable screen capture.
 */
class FlowExecutionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var executionEngine: FlowExecutionEngine
    private lateinit var repository: FlowRepository
    private var screenCaptureProvider: ScreenCaptureProvider? = null

    // Overlay
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null

    companion object {
        private const val EXTRA_FLOW_ID = "extra_flow_id"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val ACTION_STOP = "com.autonion.automationcompanion.flow.STOP"

        fun createIntent(
            context: Context,
            flowId: String,
            resultCode: Int? = null,
            resultData: Intent? = null
        ): Intent {
            return Intent(context, FlowExecutionService::class.java).apply {
                putExtra(EXTRA_FLOW_ID, flowId)
                if (resultCode != null) putExtra(EXTRA_RESULT_CODE, resultCode)
                if (resultData != null) putExtra(EXTRA_RESULT_DATA, resultData)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = FlowRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            DebugLogger.info(applicationContext, DBG_CATEGORY, "Stop Requested", "Stop action received via notification", TAG)
            stopExecution()
            return START_NOT_STICKY
        }

        val flowId = intent?.getStringExtra(EXTRA_FLOW_ID)
        if (flowId == null) {
            Log.e(TAG, "No flow ID provided")
            DebugLogger.error(applicationContext, DBG_CATEGORY, "Service Error", "No flow ID provided to service", TAG)
            stopSelf()
            return START_NOT_STICKY
        }

        // Determine if MediaProjection data is present
        val hasMediaProjection = intent.hasExtra(EXTRA_RESULT_CODE) && intent.hasExtra(EXTRA_RESULT_DATA)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaProjection) {
            startForeground(NOTIFICATION_ID, buildNotification("Loading flow..."), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Loading flow..."))
        }

        // Initialize MediaProjection-based screen capture if consent data is present
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
            Log.d(TAG, "MediaProjection consent available — initializing ScreenCaptureProvider")
            DebugLogger.info(applicationContext, DBG_CATEGORY, "MediaProjection Ready", "Screen capture initialized for visual nodes", TAG)
            screenCaptureProvider = ScreenCaptureProvider(this).also {
                it.start(resultCode, resultData)
            }
        } else {
            Log.d(TAG, "No MediaProjection consent — visual trigger and ML nodes will run in degraded mode: resultCode=$resultCode, data=$resultData")
            DebugLogger.warning(applicationContext, DBG_CATEGORY, "No MediaProjection", "Visual trigger and Screen ML nodes will run in degraded mode", TAG)
        }

        // Create the engine with the (possibly null) capture provider
        executionEngine = FlowExecutionEngine(applicationContext, screenCaptureProvider)

        showOverlay()
        startFlowExecution(flowId)

        return START_NOT_STICKY
    }

    private fun startFlowExecution(flowId: String) {
        val graph = repository.load(flowId)
        if (graph == null) {
            Log.e(TAG, "Flow not found: $flowId")
            DebugLogger.error(applicationContext, DBG_CATEGORY, "Flow Not Found", "Could not load flow with ID: $flowId", TAG)
            stopSelf()
            return
        }

        Log.d(TAG, "Starting flow execution: ${graph.name}")
        DebugLogger.info(applicationContext, DBG_CATEGORY, "Service Starting Flow", "Loaded flow '${graph.name}' (${graph.nodes.size} nodes, ${graph.edges.size} edges)", TAG)

        // Observe state changes
        scope.launch {
            executionEngine.state.collect { state ->
                when (state) {
                    is FlowExecutionState.Running -> {
                        updateNotification("Running: ${state.currentNodeLabel}")
                        updateOverlayStatus(state.currentNodeLabel)
                    }
                    is FlowExecutionState.Completed -> {
                        Log.d(TAG, "Flow completed")
                        DebugLogger.success(applicationContext, DBG_CATEGORY, "Flow Complete", "Flow finished — stopping service", TAG)
                        updateNotification("Flow completed")
                        stopExecution()
                    }
                    is FlowExecutionState.Error -> {
                        Log.e(TAG, "Flow error: ${state.message}")
                        DebugLogger.error(applicationContext, DBG_CATEGORY, "Flow Error", "Error: ${state.message}", TAG)
                        updateNotification("Error: ${state.message}")
                        stopExecution()
                    }
                    is FlowExecutionState.Stopped -> {
                        stopExecution()
                    }
                    else -> { /* Idle, NodeCompleted — no action needed */ }
                }
            }
        }

        executionEngine.execute(graph, scope)
    }

    // ─── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Flow Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status while a flow is running"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, FlowExecutionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Flow Running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ─── Floating Overlay (Panic Button + Status) ────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 16
                y = 200
            }

            overlayView = FrameLayout(this).apply {
                // Simple overlay: status text + stop button
                val statusText = TextView(this@FlowExecutionService).apply {
                    id = android.R.id.text1
                    text = "Flow Running"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                    setPadding(16, 8, 16, 8)
                }

                val stopButton = ImageButton(this@FlowExecutionService).apply {
                    setImageResource(android.R.drawable.ic_delete)
                    setBackgroundColor(0xFFFF1744.toInt())
                    setPadding(24, 24, 24, 24)
                    setOnClickListener {
                        executionEngine.stop()
                    }
                }

                setBackgroundColor(0xCC1A1C1E.toInt())
                setPadding(16, 8, 16, 8)
                addView(statusText)
                addView(stopButton)
            }

            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}")
        }
    }

    private fun updateOverlayStatus(nodeLabel: String) {
        overlayView?.findViewWithTag<TextView>(android.R.id.text1)
            ?.let { it.text = "▶ $nodeLabel" }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
            overlayView = null
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay: ${e.message}")
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────

    private fun stopExecution() {
        executionEngine.stop()
        screenCaptureProvider?.stop()
        screenCaptureProvider = null
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        executionEngine.stop()
        screenCaptureProvider?.stop()
        screenCaptureProvider = null
        removeOverlay()
        scope.cancel()
    }
}
