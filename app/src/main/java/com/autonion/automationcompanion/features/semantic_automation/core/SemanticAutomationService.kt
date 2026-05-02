package com.autonion.automationcompanion.features.semantic_automation.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.screen_understanding_ml.core.MediaProjectionCore
import com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus
import com.autonion.automationcompanion.features.semantic_automation.ui.FloatingStopOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns a MediaProjection and drives the
 * [SemanticAutomationEngine] screen loop.
 *
 * Started from [SemanticAutomationScreen] via an explicit intent
 * containing the MediaProjection result and the raw user command.
 */
class SemanticAutomationService : Service() {

    companion object {
        private const val TAG = "SemanticAutoSvc"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "semantic_automation_channel"

        const val ACTION_START = "ACTION_START_SEMANTIC"
        const val ACTION_STOP = "ACTION_STOP_SEMANTIC"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_COMMAND = "command"

        private val _activeEngine = kotlinx.coroutines.flow.MutableStateFlow<SemanticAutomationEngine?>(null)
        val activeEngine: kotlinx.coroutines.flow.StateFlow<SemanticAutomationEngine?> = _activeEngine.asStateFlow()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjectionCore: MediaProjectionCore? = null
    private var engine: SemanticAutomationEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var keepAwakeView: View? = null
    private var floatingStopOverlay: FloatingStopOverlay? = null

    @Volatile
    private var latestBitmap: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine = SemanticAutomationEngine(this)
        _activeEngine.value = engine
        Log.d(TAG, "Service created")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        _activeEngine.value = null
        engine?.cleanup()
        mediaProjectionCore?.stopProjection()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        // Remove floating stop overlay
        floatingStopOverlay?.hide()
        floatingStopOverlay = null
        keepAwakeView?.let {
            try {
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove keepAwakeView", e)
            }
            keepAwakeView = null
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                engine?.stop()
                stopSelf()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: ""

        if (resultCode == 0 || data == null || command.isBlank()) {
            Log.e(TAG, "Missing MediaProjection data or command")
            stopSelf()
            return
        }

        DebugLogger.info(
            this, LogCategory.UI_RECOGNITION_AI,
            "Semantic automation started",
            "Command: \"$command\"",
            TAG
        )

        // Acquire WakeLock to keep screen on while reasoning
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SemanticAutomation::WakeLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes max*/)

        // Inject an invisible overlay to force the screen to stay on (Robust against Chinese ROMs)
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            keepAwakeView = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            val overlayParams = WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSPARENT
            )
            overlayParams.gravity = Gravity.TOP or Gravity.START
            windowManager.addView(keepAwakeView, overlayParams)
            Log.d(TAG, "keepAwakeView overlay injected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject keepAwakeView overlay", e)
        }

        // Show the floating stop button overlay (visible across all apps)
        try {
            floatingStopOverlay = FloatingStopOverlay(this)
            floatingStopOverlay?.show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating stop overlay", e)
        }

        val metrics = resources.displayMetrics
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionCore = MediaProjectionCore(this, mediaProjectionManager!!)
        mediaProjectionCore?.startProjection(resultCode, data, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

        // Collect screenshots into latestBitmap
        scope.launch {
            mediaProjectionCore?.screenCaptureFlow?.collect { bitmap ->
                latestBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            }
        }

        // Run the engine loop (with initial delay for projection to start)
        scope.launch {
            // Wait for MediaProjection to start producing frames
            kotlinx.coroutines.delay(2000)

            engine?.runLoop(command) {
                // Screenshot provider — returns the latest captured frame
                latestBitmap
            }

            // When engine finishes, stop the service
            val finalStatus = engine?.status?.value
            if (finalStatus == AutomationStatus.COMPLETED || finalStatus == AutomationStatus.FAILED) {
                Log.d(TAG, "Engine finished with status: $finalStatus")
                floatingStopOverlay?.hide()
                kotlinx.coroutines.delay(3000)
                stopSelf()
            }
        }
    }

    /** Expose engine for UI observation */
    fun getEngine(): SemanticAutomationEngine? = engine

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Semantic Automation",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Semantic Agent Active")
            .setContentText("AI automation running…")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
