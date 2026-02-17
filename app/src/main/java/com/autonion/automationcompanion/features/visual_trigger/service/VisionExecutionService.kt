package com.autonion.automationcompanion.features.visual_trigger.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.core.vision.VisionNativeBridge
import com.autonion.automationcompanion.features.visual_trigger.core.VisionMediaProjection
import com.autonion.automationcompanion.features.visual_trigger.data.VisionRepository
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class VisionExecutionService : Service() {

    companion object {
        private const val TAG = "VisionExecution"
        private const val CHANNEL_ID = "vision_execution_channel"
        private const val NOTIFICATION_ID = 1002
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private var visionProjection: VisionMediaProjection? = null
    private var repository: VisionRepository? = null
    private var activePreset: VisionPreset? = null

    // Overlay
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isPaused = true  // Start paused — user taps play to begin
    private var playPauseIcon: ImageView? = null

    @Volatile
    private var isRunning = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = VisionRepository(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        VisionNativeBridge.init()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_START_EXECUTION" -> {
                val resultCode = intent.getIntExtra("EXTRA_RESULT_CODE", 0)
                @Suppress("DEPRECATION")
                val resultData = intent.getParcelableExtra<Intent>("EXTRA_RESULT_DATA")
                val presetId = intent.getStringExtra("EXTRA_PRESET_ID")

                if (resultCode != 0 && resultData != null && presetId != null) {
                    startForegroundServiceNotification()
                    showExecutionOverlay()
                    startExecution(resultCode, resultData, presetId)
                } else {
                    Log.e(TAG, "Missing params: resultCode=$resultCode, data=$resultData, presetId=$presetId")
                    stopSelf()
                }
            }
            "ACTION_STOP_EXECUTION" -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Vision Execution", NotificationManager.IMPORTANCE_LOW)
        } else {
            TODO("VERSION.SDK_INT < O")
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val stopIntent = Intent(this, VisionExecutionService::class.java).apply {
            action = "ACTION_STOP_EXECUTION"
        }
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vision Automation Running")
            .setContentText("Scanning screen...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ── Execution Overlay ──────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showExecutionOverlay() {
        if (overlayView != null) return
        val dp = resources.displayMetrics.density

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - 150
            y = resources.displayMetrics.heightPixels / 4
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28 * dp
                setColor(0xE61A1A2E.toInt())
                setStroke((1.5f * dp).toInt(), 0x40FFFFFF)
            }
            elevation = 8 * dp
        }

        val playPauseBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_media_play)  // Start paused → show play icon
            setColorFilter(0xFF00C853.toInt())
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x2200C853) }
        }
        playPauseBtn.layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
        playPauseIcon = playPauseBtn

        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams((8 * dp).toInt(), 1)

        val exitBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(0xFFFF1744.toInt())
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22FF1744) }
        }
        exitBtn.layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())

        container.addView(playPauseBtn)
        container.addView(spacer)
        container.addView(exitBtn)

        // Draggable — handle ALL touch events at container level
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f; var isDragging = false
        container.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x; initialY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    if (isDragging) {
                        lp.x = initialX + dx.toInt()
                        lp.y = initialY + dy.toInt()
                        windowManager?.updateViewLayout(container, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // It was a tap — find which child was hit
                        val x = event.x.toInt()
                        val y = event.y.toInt()
                        val playRect = android.graphics.Rect()
                        playPauseBtn.getHitRect(playRect)
                        val exitRect = android.graphics.Rect()
                        exitBtn.getHitRect(exitRect)

                        when {
                            playRect.contains(x, y) -> togglePause()
                            exitRect.contains(x, y) -> stopSelf()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        overlayView = container
        windowManager?.addView(overlayView, lp)
    }

    private fun togglePause() {
        isPaused = !isPaused
        playPauseIcon?.setImageResource(
            if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )
        Log.d(TAG, if (isPaused) "PAUSED" else "RESUMED")
    }

    // ── Execution Logic ───────────────────────────────────────────────

    private fun startExecution(resultCode: Int, resultData: Intent, presetId: String) {
        scope.launch {
            activePreset = repository?.getPreset(presetId)
            if (activePreset == null) {
                Log.e(TAG, "Preset not found: $presetId")
                stopSelf()
                return@launch
            }

            Log.d(TAG, "▶ Starting execution: '${activePreset?.name}', ${activePreset?.regions?.size} regions, mode=${activePreset?.executionMode}")

            VisionNativeBridge.nativeClearTemplates()

            activePreset?.regions?.forEach { region ->
                val bitmap = android.graphics.BitmapFactory.decodeFile(region.templatePath)
                if (bitmap != null) {
                    Log.d(TAG, "  ✓ Template ID=${region.id}: ${bitmap.width}x${bitmap.height}")
                    VisionNativeBridge.addTemplate(region.id, bitmap)
                } else {
                    Log.e(TAG, "  ✗ Failed to decode template: ${region.templatePath}")
                }
            }

            val metrics = resources.displayMetrics
            Log.d(TAG, "Screen: ${metrics.widthPixels}x${metrics.heightPixels}")

            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            visionProjection = VisionMediaProjection(this@VisionExecutionService, mpManager)
            visionProjection?.startProjection(resultCode, resultData, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

            Log.d(TAG, "Projection started, collecting frames...")
            val connected = VisionActionExecutor.isConnected()
            Log.d(TAG, "AccessibilityService connected: $connected")

            var frameCount = 0

            visionProjection?.screenCaptureFlow?.collect { bitmap ->
                frameCount++
                if (!isPaused && isRunning) {
                    if (frameCount <= 5 || frameCount % 20 == 0) {
                        Log.d(TAG, "Frame #$frameCount: ${bitmap.width}x${bitmap.height}")
                    }
                    processFrame(bitmap)
                } else if (isPaused && frameCount % 50 == 0) {
                    Log.d(TAG, "Skipping frame #$frameCount (paused)")
                }
                delay(500)
            }
        }
    }

    private suspend fun processFrame(bitmap: Bitmap) {
        if (!isRunning) return
        val preset = activePreset ?: return

        try {
            val results = VisionNativeBridge.match(bitmap)
            if (!isRunning) return

            // Log match results
            results.forEach { match ->
                Log.d(TAG, "  Match ID=${match.id}: matched=${match.matched}, score=${match.score}, at=(${match.x},${match.y}), size=${match.width}x${match.height}")
            }

            if (preset.executionMode == ExecutionMode.MANDATORY_SEQUENTIAL) {
                handleSequentialExecution(preset, results)
            } else {
                val matches = results.filter { it.matched }
                if (matches.isEmpty()) {
                    Log.d(TAG, "  No matches above threshold (need score≥0.75)")
                }
                matches.forEach { match ->
                    val region = preset.regions.find { it.id == match.id }
                    if (region != null) {
                        val cx = match.x + match.width / 2
                        val cy = match.y + match.height / 2
                        Log.d(TAG, "  ▶ Executing ${region.action} at ($cx, $cy)")
                        val success = executeAction(region, cx, cy)
                        Log.d(TAG, "  Action result: $success")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in processFrame", e)
        }
    }

    private var currentStepIndex = 0
    private var lastActionTime = 0L

    private suspend fun handleSequentialExecution(
        preset: VisionPreset,
        results: Array<com.autonion.automationcompanion.core.vision.MatchResultNative>
    ) {
        if (System.currentTimeMillis() - lastActionTime < 2000) return

        if (currentStepIndex >= preset.regions.size) {
            currentStepIndex = 0
            return
        }

        val targetRegion = preset.regions[currentStepIndex]
        val match = results.find { it.id == targetRegion.id }

        if (match != null && match.matched) {
            Log.d(TAG, "Sequential step $currentStepIndex matched: ID ${targetRegion.id}")
            val success = executeAction(targetRegion, match.x + match.width / 2, match.y + match.height / 2)
            if (success) {
                currentStepIndex++
                lastActionTime = System.currentTimeMillis()
            }
        }
    }

    private suspend fun executeAction(region: VisionRegion, screenX: Int, screenY: Int): Boolean {
        val point = android.graphics.PointF(screenX.toFloat(), screenY.toFloat())
        return VisionActionExecutor.execute(region.action, point)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying...")

        // 1. Pause first so the loop stops calling native match
        isPaused = true
        isRunning = false

        // 2. Stop projection so no more frames arrive
        visionProjection?.stopProjection()
        visionProjection = null

        // 3. Cancel coroutines and wait for them to finish
        job.cancel()

        // 4. Remove overlay
        if (overlayView != null) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            overlayView = null
        }

        // 5. Release native resources after delay (let any in-flight JNI call finish)
        //    The C++ side now uses a mutex, so this is safe as long as
        //    the match call finishes before clear is called.
        Handler(Looper.getMainLooper()).postDelayed({
            try { VisionNativeBridge.release() } catch (_: Exception) {}
            Log.d(TAG, "Native resources released")
        }, 1000)
    }
}
