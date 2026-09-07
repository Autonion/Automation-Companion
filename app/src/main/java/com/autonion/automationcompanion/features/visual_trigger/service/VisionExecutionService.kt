package com.autonion.automationcompanion.features.visual_trigger.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.core.ui.OverlayStyles
import com.autonion.automationcompanion.core.vision.MatchResultNative
import com.autonion.automationcompanion.core.vision.VisionNativeBridge
import com.autonion.automationcompanion.core.vision.VisionCaptureGeometry
import com.autonion.automationcompanion.core.vision.LatestVisionDispatch
import com.autonion.automationcompanion.core.vision.remapVisionBounds
import com.autonion.automationcompanion.core.vision.isFreshVisionFrame
import com.autonion.automationcompanion.core.vision.TapBounds
import com.autonion.automationcompanion.core.vision.predictMovingTap
import com.autonion.automationcompanion.features.visual_trigger.core.VisionFrame
import com.autonion.automationcompanion.features.visual_trigger.models.VisionMatchMode
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.visual_trigger.core.VisionMediaProjection
import com.autonion.automationcompanion.features.visual_trigger.data.VisionRepository
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode
import com.autonion.automationcompanion.features.visual_trigger.models.TapDispatchMode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VisionExecutionService : Service() {

    companion object {
        private const val TAG = "VisionExecution"
        private const val CHANNEL_ID = "vision_execution_channel"
        private const val NOTIFICATION_ID = 1002
        private const val STUCK_FULLSCREEN_FALLBACK_MS = 10_000L  // 10s → fallback to full-screen search
        private const val STUCK_RESTART_MS = 30_000L               // 30s → restart sequence from step 0
        private const val DETECT_ACTION_COOLDOWN_MS = 1_500L
        private const val DETECT_RELEASE_MISS_FRAMES = 2
        private const val DETECT_POSITION_BUCKET_PX = 32
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    private var visionProjection: VisionMediaProjection? = null
    private var repository: VisionRepository? = null
    private var activePreset: VisionPreset? = null
    private data class CaptureConfiguration(
        val display: CaptureDisplayMetrics,
        val geometry: VisionCaptureGeometry,
        val preset: VisionPreset,
        val readerGeneration: Int = -1,
        val validAfterMs: Long = 0
    )
    private data class Detection(
        val configuration: CaptureConfiguration,
        val results: Array<MatchResultNative>,
        val observedAtMs: Long,
        val detectedAtMs: Long,
        val generation: Int
    )
    private val captureMutex = Mutex()
    private val detections = LatestVisionDispatch<Detection>()
    @Volatile private var captureConfiguration: CaptureConfiguration? = null
    @Volatile private var captureChanging = false
    private var referenceDisplay: CaptureDisplayMetrics? = null
    @Volatile private var desiredDisplay: CaptureDisplayMetrics? = null
    private var resizeJob: Job? = null
    @Volatile private var lastGestureStartedAtMs = 0L
    @Volatile private var gestureInFlight = false
    private var dispatchStateGeneration = -1
    private var overlappedScans = 0
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) requestDisplayRefresh()
        }
    }

    // Overlay
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    @Volatile private var isPaused = true
    @Volatile private var executionGeneration = 0
    @Volatile private var resetMotionRequested = false
    private var playPauseIcon: ImageView? = null
    @Volatile private var overlayInteracting = false

    @Volatile
    private var isRunning = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = VisionRepository(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        VisionNativeBridge.init()
        Log.d(TAG, "Service created")
        DebugLogger.info(applicationContext, LogCategory.VISUAL_TRIGGER, "Service Created", "VisionExecutionService initialized", TAG)
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
                    DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Start Failed", "Missing params: resultCode=$resultCode, presetId=$presetId", TAG)
                    stopSelf()
                }
            }
            "ACTION_TOGGLE_PAUSE" -> {
                togglePause()
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
        val toggleIntent = Intent(this, VisionExecutionService::class.java).apply {
            action = "ACTION_TOGGLE_PAUSE"
        }
        val togglePendingIntent = android.app.PendingIntent.getService(
            this, 1, toggleIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPendingIntent = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vision Automation Running")
            .setContentText(if (isPaused) "Paused" else "Scanning screen...")
            .setSmallIcon(com.autonion.automationcompanion.R.drawable.ic_notification)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                togglePendingIntent
            )
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
        val panelWidthDp = OverlayStyles.CLOSE_BUTTON_SIZE_DP * 2 +
            OverlayStyles.BUTTON_SPACING_DP + OverlayStyles.PANEL_PADDING_H_DP * 2

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - ((panelWidthDp + 16) * dp).toInt()).coerceAtLeast(0)
            y = resources.displayMetrics.heightPixels / 4
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val horizontalPadding = (OverlayStyles.PANEL_PADDING_H_DP * dp).toInt()
            val verticalPadding = (OverlayStyles.PANEL_PADDING_V_DP * dp).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            background = OverlayStyles.createPanelBackground(dp)
            elevation = OverlayStyles.PANEL_ELEVATION_DP * dp
        }

        val playPauseBtn = OverlayStyles.createIconButton(
            this, android.R.drawable.ic_media_play, "Start preset"
        ) { togglePause() }.apply { background = null }
        playPauseIcon = playPauseBtn

        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams((OverlayStyles.BUTTON_SPACING_DP * dp).toInt(), 1)

        val exitBtn = OverlayStyles.createIconButton(
            this, android.R.drawable.ic_menu_close_clear_cancel, "Close preset"
        ) { stopSelf() }.apply { background = null }

        container.addView(playPauseBtn)
        container.addView(spacer)
        container.addView(exitBtn)

        // Share drag handling with the buttons so they stay accessible and draggable.
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f; var isDragging = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        val dragListener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    overlayInteracting = true
                    initialX = lp.x; initialY = lp.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) isDragging = true
                    if (isDragging) {
                        val displayFrame = android.graphics.Rect()
                        container.getWindowVisibleDisplayFrame(displayFrame)
                        lp.x = (initialX + dx.toInt()).coerceIn(0, (displayFrame.width() - container.width).coerceAtLeast(0))
                        lp.y = (initialY + dy.toInt()).coerceIn(0, (displayFrame.height() - container.height).coerceAtLeast(0))
                        windowManager?.updateViewLayout(container, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    overlayInteracting = false
                    if (!isDragging) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    overlayInteracting = false
                    isDragging = false
                    true
                }
                else -> false
            }
        }

        container.setOnTouchListener(dragListener)
        playPauseBtn.setOnTouchListener(dragListener)
        exitBtn.setOnTouchListener(dragListener)

        overlayView = container
        overlayLayoutParams = lp
        windowManager?.addView(overlayView, lp)
        updateExecutionOverlayTouchPolicy()
    }

    private fun togglePause() {
        isPaused = !isPaused
        executionGeneration++
        detections.clear()
        if (!isPaused) {
            resetMotionRequested = true
        }
        playPauseIcon?.setImageResource(
            if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        )
        playPauseIcon?.contentDescription = if (isPaused) "Resume preset" else "Pause preset"
        updateExecutionOverlayTouchPolicy()
        startForegroundServiceNotification()
        Log.d(TAG, if (isPaused) "PAUSED" else "RESUMED")
        DebugLogger.info(applicationContext, LogCategory.VISUAL_TRIGGER, if (isPaused) "Paused" else "Resumed", if (isPaused) "Execution paused by user" else "Execution resumed by user", TAG)
    }

    private fun updateExecutionOverlayTouchPolicy() {
        val view = overlayView ?: return
        val lp = overlayLayoutParams ?: return
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SECURE
        view.visibility = View.VISIBLE
        view.alpha = 1.0f
        try {
            windowManager?.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update execution overlay touch policy", e)
        }
    }

    // ── Execution Logic ───────────────────────────────────────────────

    // Called on Main immediately before dispatch; layout coordinates alone omit system insets.
    private fun overlapsExecutionOverlay(x: Float, y: Float, verticalRadius: Float = 0f): Boolean {
        val view = overlayView ?: return false
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + view.width &&
            y + verticalRadius >= location[1] && y - verticalRadius <= location[1] + view.height
    }

    private fun startExecution(resultCode: Int, resultData: Intent, presetId: String) {
        scope.launch {
            activePreset = repository?.getPreset(presetId)
            if (activePreset == null) {
                Log.e(TAG, "Preset not found: $presetId")
                DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Preset Not Found", "Preset ID: $presetId", TAG)
                stopSelf()
                return@launch
            }

            Log.d(TAG, "▶ Starting execution: '${activePreset?.name}', ${activePreset?.regions?.size} regions, mode=${activePreset?.executionMode}")
            DebugLogger.info(applicationContext, LogCategory.VISUAL_TRIGGER, "Execution Started", "Preset: '${activePreset?.name}', ${activePreset?.regions?.size} regions, mode=${activePreset?.executionMode}", TAG)

            resetExecutionState()
            val invalidMovingRegion = activePreset?.regions?.firstOrNull {
                it.matchMode == VisionMatchMode.MOVING &&
                    (it.action !is VisionAction.Click ||
                        activePreset?.executionMode != ExecutionMode.DETECT_ONLY)
            }
            if (invalidMovingRegion != null) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@VisionExecutionService,
                        "Rotating targets require React to matches and Tap", android.widget.Toast.LENGTH_LONG).show()
                }
                stopSelf()
                return@launch
            }
            val preset = activePreset ?: return@launch
            val metrics = withContext(Dispatchers.Main) { getRealDisplayMetrics() }
            referenceDisplay = metrics
            desiredDisplay = metrics
            val configuration = try {
                configureTemplates(preset, metrics)
            } catch (error: Exception) {
                Log.e(TAG, "Cannot configure vision targets", error)
                stopSelf()
                return@launch
            }
            captureConfiguration = configuration
            val geometry = configuration.geometry

            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            visionProjection = VisionMediaProjection(this@VisionExecutionService, mpManager) {
                // MediaProjection was revoked by the OS
                Log.w(TAG, "MediaProjection lost — stopping execution")
                DebugLogger.warning(applicationContext, LogCategory.VISUAL_TRIGGER,
                    "Screen capture lost",
                    "MediaProjection revoked by the system — restart required", TAG)
                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this@VisionExecutionService,
                        "Screen capture lost — please restart", android.widget.Toast.LENGTH_LONG).show()
                }
                stopSelf()
            }
            visionProjection?.onCapturedContentResize = { _, _ -> requestDisplayRefresh() }
            val captureStartedAtMs = SystemClock.uptimeMillis()
            visionProjection?.startProjection(resultCode, resultData, geometry.captureWidth, geometry.captureHeight, metrics.densityDpi)
            captureConfiguration = configuration.copy(
                readerGeneration = visionProjection?.captureGeneration() ?: -1,
                validAfterMs = captureStartedAtMs)
            withContext(Dispatchers.Main) {
                getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
                requestDisplayRefresh()
            }

            if (preset.executionMode == ExecutionMode.DETECT_ONLY) {
                launch {
                    detections.consume({ executionGeneration }, SystemClock::uptimeMillis,
                        { lastGestureStartedAtMs }, ::dispatchDetection)
                }
            }

            Log.d(TAG, "Projection started, collecting frames...")
            val connected = VisionActionExecutor.isConnected()
            Log.d(TAG, "AccessibilityService connected: $connected")
            if (!connected) {
                DebugLogger.warning(applicationContext, LogCategory.VISUAL_TRIGGER, "No Accessibility", "AccessibilityService not connected — actions may fail", TAG)
            }

            val moving = activePreset?.regions?.any { it.matchMode == VisionMatchMode.MOVING } == true
            val targetCycleMs = if (moving) 33L else if (
                activePreset?.executionMode == ExecutionMode.DETECT_ONLY
            ) 0L else 400L
            visionProjection?.frames?.collect { frame ->
                val startedAt = SystemClock.uptimeMillis()
                val detection = captureMutex.withLock {
                    if (resetMotionRequested) {
                        resetMotionRequested = false
                        VisionNativeBridge.resetMotion()
                    }
                    try {
                        if (!isPaused && isRunning && !captureChanging) processFrame(frame) else null
                    } finally { frame.close() }
                }
                if (detection != null) {
                    if (preset.executionMode == ExecutionMode.DETECT_ONLY) {
                        detections.offer(detection, detection.generation, detection.observedAtMs)
                    } else dispatchDetection(detection)
                }
                val remaining = targetCycleMs - (SystemClock.uptimeMillis() - startedAt)
                if (remaining > 0) delay(remaining)
            }
        }
    }

    private var lastTimingLogMs = 0L
    private val movingAttempts = mutableMapOf<Pair<Int, Int>, Pair<Long, Int>>()

    private fun configureTemplates(preset: VisionPreset, display: CaptureDisplayMetrics): CaptureConfiguration {
        val reference = checkNotNull(referenceDisplay)
        val geometry = VisionCaptureGeometry.create(display.width, display.height,
            preset.regions.minOfOrNull { minOf(it.width, it.height) } ?: 24,
            allowScaling = preset.executionMode == ExecutionMode.DETECT_ONLY &&
                preset.regions.none { it.matchMode == VisionMatchMode.MOVING })
        val runtimeRegions = preset.regions.map { region ->
            val sourceSize = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(region.sourceCapturePath ?: preset.captureImagePath, sourceSize)
            val referenceWidth = sourceSize.outWidth.takeIf { it > 0 } ?: reference.width
            val referenceHeight = sourceSize.outHeight.takeIf { it > 0 } ?: reference.height
            val source = region.customSearchRect() ?: if (preset.executionMode == ExecutionMode.DETECT_ONLY) {
                android.graphics.Rect(0, 0, referenceWidth, referenceHeight)
            } else region.toSearchRect()
            val mapped = remapVisionBounds(TapBounds(source.left, source.top, source.right, source.bottom),
                referenceWidth, referenceHeight, display.width, display.height)
            region.copy(searchX = mapped.left, searchY = mapped.top,
                searchWidth = mapped.right - mapped.left, searchHeight = mapped.bottom - mapped.top)
        }
        VisionNativeBridge.clearTemplates()
        preset.regions.zip(runtimeRegions).forEach { (original, region) ->
            val bitmap = checkNotNull(android.graphics.BitmapFactory.decodeFile(region.templatePath)) {
                "Cannot decode target ${region.id}"
            }
            try {
                val roi = region.toSearchRect()
                require(roi.width() > 0 && roi.height() > 0) { "Target ${region.id} search area is outside the screen" }
                val scaledRect = android.graphics.Rect(geometry.captureX(roi.left), geometry.captureY(roi.top),
                    geometry.captureRight(roi.right), geometry.captureBottom(roi.bottom))
                val scaled = Bitmap.createScaledBitmap(bitmap, geometry.templateWidth(bitmap.width),
                    geometry.templateHeight(bitmap.height), true)
                try {
                    val adaptive = preset.executionMode != ExecutionMode.DETECT_ONLY && original.customSearchRect() == null
                    VisionNativeBridge.addTemplate(region.id, scaled,
                        scaledRect.left, scaledRect.top, scaledRect.width(), scaledRect.height(),
                        region.effectiveThreshold, allowFullscreenFallback = adaptive, trackRoiToMatch = adaptive,
                        moving = region.matchMode == VisionMatchMode.MOVING)
                    Log.d(TAG, "Template ID=${region.id}: ${bitmap.width}x${bitmap.height} ROI=$roi mode=${region.matchMode} threshold=${region.effectiveThreshold}")
                } finally { if (scaled !== bitmap) scaled.recycle() }
            } finally { bitmap.recycle() }
        }
        Log.i(TAG, "Vision config: pipeline=fast-v3 size=adaptive screen=${display.width}x${display.height} rotation=${display.rotation} capture=${geometry.captureWidth}x${geometry.captureHeight} dispatch=${preset.tapDispatchMode} overlap=${preset.executionMode == ExecutionMode.DETECT_ONLY}")
        val dispatchRegions = runtimeRegions.mapIndexed { index, region ->
            if (preset.executionMode != ExecutionMode.DETECT_ONLY && preset.regions[index].customSearchRect() == null) {
                region.copy(searchX = null, searchY = null, searchWidth = null, searchHeight = null)
            } else region
        }
        return CaptureConfiguration(display, geometry, preset.copy(regions = dispatchRegions))
    }

    // Runs on Main for both display callbacks and the final pre-dispatch check.
    private fun requestDisplayRefresh(): Boolean {
        if (!isRunning || captureConfiguration == null) return false
        val next = getRealDisplayMetrics()
        if (next == desiredDisplay) return !captureChanging
        desiredDisplay = next
        captureChanging = true
        executionGeneration++
        detections.clear()
        resizeJob?.cancel()
        Log.i(TAG, "Vision orientation changing: ${next.width}x${next.height} rotation=${next.rotation}; taps suspended")
        resizeJob = scope.launch {
            // Wait for the app and surface to settle, without freezing the overlay.
            delay(100)
            try {
                captureMutex.withLock {
                    val preset = activePreset ?: return@withLock
                    val configured = configureTemplates(preset, next)
                    val projection = visionProjection ?: return@withLock
                    val resizeStartedAtMs = SystemClock.uptimeMillis()
                    val readerGeneration = projection.resizeCapture(configured.geometry.captureWidth,
                        configured.geometry.captureHeight, next.densityDpi)
                    captureConfiguration = configured.copy(readerGeneration = readerGeneration,
                        validAfterMs = resizeStartedAtMs)
                }
                withContext(Dispatchers.Main) {
                    if (isRunning && desiredDisplay == next) {
                        clampExecutionOverlay()
                        captureChanging = false
                        Log.i(TAG, "Vision orientation ready: ${next.width}x${next.height}; awaiting fresh frame, paused=$isPaused")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Unable to resize vision capture", error)
                withContext(Dispatchers.Main) { stopSelf() }
            }
        }
        return false
    }

    private fun clampExecutionOverlay() {
        val view = overlayView ?: return
        val lp = overlayLayoutParams ?: return
        val frame = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(frame)
        lp.x = lp.x.coerceIn(0, (frame.width() - view.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (frame.height() - view.height).coerceAtLeast(0))
        windowManager?.updateViewLayout(view, lp)
    }

    private fun canDispatch(generation: Int): Boolean =
        !isPaused && isRunning && !overlayInteracting && generation == executionGeneration && requestDisplayRefresh()

    private suspend fun <T> duringGesture(block: suspend () -> T): T {
        lastGestureStartedAtMs = SystemClock.uptimeMillis()
        gestureInFlight = true
        return try { block() } finally { gestureInFlight = false }
    }

    private fun processFrame(frame: VisionFrame): Detection? {
        if (!isRunning || isPaused || captureChanging) return null
        val configuration = captureConfiguration ?: return null
        val geometry = configuration.geometry
        if (frame.captureGeneration != configuration.readerGeneration ||
            frame.width != geometry.captureWidth || frame.height != geometry.captureHeight ||
            frame.observedAtMs < configuration.validAfterMs) return null
        // Do not spend a native scan on coordinates the dispatcher must discard.
        if (configuration.preset.executionMode == ExecutionMode.DETECT_ONLY &&
            frame.observedAtMs <= lastGestureStartedAtMs) return null
        val generation = executionGeneration
        val overlapping = gestureInFlight
        try {
            val plane = frame.image.planes[0]
            require(plane.pixelStride == 4) { "Unsupported capture pixel stride" }
            val startedAt = SystemClock.uptimeMillis()
            val results = try {
                VisionNativeBridge.matchRgba(plane.buffer, frame.width, frame.height, plane.rowStride, frame.observedAtMs)
                    .map(geometry::toScreen).toTypedArray()
            } finally {
                frame.close()
            }
            if (!isRunning || isPaused || captureChanging || generation != executionGeneration) return null
            val now = SystemClock.uptimeMillis()
            if (overlapping) overlappedScans++
            if (now - lastTimingLogMs >= 2000) {
                Log.i(TAG, "Vision timing: match=${now - startedAt}ms frameAge=${now - frame.observedAtMs}ms captureQueue=${frame.acquiredAtMs - frame.observedAtMs}ms clock=${frame.timestampSource} hits=${results.count { it.matched }} best=${results.maxOfOrNull { it.score }} overlappedScans=$overlappedScans generation=$generation")
                lastTimingLogMs = now
            }
            return Detection(configuration, results, frame.observedAtMs, now, generation)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in processFrame", e)
            DebugLogger.error(applicationContext, LogCategory.VISUAL_TRIGGER, "Frame Error", "Error processing frame: ${e.message}", TAG)
            return null
        }
    }

    private suspend fun dispatchDetection(detection: Detection) {
        val generation = detection.generation
        if (!isRunning || isPaused || captureChanging || generation != executionGeneration) return
        val dispatchAtMs = SystemClock.uptimeMillis()
        if (dispatchAtMs - lastDispatchTimingLogMs >= 2000) {
            Log.i(TAG, "Vision dispatch timing: queueWait=${dispatchAtMs - detection.detectedAtMs}ms frameAge=${dispatchAtMs - detection.observedAtMs}ms generation=$generation")
            lastDispatchTimingLogMs = dispatchAtMs
        }
        if (dispatchStateGeneration != generation) {
            resetDetectOnlyState()
            movingAttempts.clear()
            dispatchStateGeneration = generation
        }
        val preset = detection.configuration.preset
        val geometry = detection.configuration.geometry
        val results = detection.results
        try {
            when (preset.executionMode) {
                ExecutionMode.MANDATORY_SEQUENTIAL -> handleSequentialExecution(preset, results, generation, skipOnMiss = false)
                ExecutionMode.OPTIONAL_SEQUENTIAL -> handleSequentialExecution(preset, results, generation, skipOnMiss = true)
                ExecutionMode.DETECT_ONLY -> {
                    handleMovingExecution(preset, results, detection.observedAtMs, generation, geometry.screenWidth, geometry.screenHeight)
                    if (!isPaused && isRunning && !captureChanging && generation == executionGeneration) {
                        val staticIds = preset.regions.filter { it.matchMode == VisionMatchMode.STATIC }.map { it.id }.toSet()
                        if (staticIds.isNotEmpty()) handleDetectOnlyExecution(preset,
                            results.filter { it.id in staticIds }.toTypedArray(), detection.observedAtMs, generation)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Vision dispatch failed", error)
        }
    }

    private suspend fun handleMovingExecution(
        preset: VisionPreset,
        results: Array<MatchResultNative>,
        observedAt: Long,
        generation: Int,
        screenWidth: Int,
        screenHeight: Int
    ) {
        val now = SystemClock.uptimeMillis()
        movingAttempts.entries.removeAll { now - it.value.first > 2000 }
        val regionsById = preset.regions.associateBy { it.id }
        val pending = results.filter { match ->
            val region = regionsById[match.id]
            val attempt = movingAttempts[match.id to match.trackId]
            region?.matchMode == VisionMatchMode.MOVING && region.action is VisionAction.Click &&
                match.matched && match.observations >= 2 &&
                (attempt == null || (attempt.second < 3 && now - attempt.first >= 200))
        }
        val batchSize = if (preset.tapDispatchMode == TapDispatchMode.CONCURRENT) VisionActionExecutor.maxConcurrentTapCount() else 1
        for (batch in pending.chunked(batchSize)) {
            // Recompute immediately before each gesture. A prior tap may have
            // used most of the freshness budget for a sequential batch.
            val dispatched = withContext(Dispatchers.Main.immediate) {
                if (!canDispatch(generation)) return@withContext emptyList<MatchResultNative>()
                val taps = batch.mapNotNull { hit ->
                    val region = regionsById.getValue(hit.id)
                    val roi = region.toSearchRect()
                    val point = predictMovingTap(hit, observedAt, SystemClock.uptimeMillis(),
                        TapBounds(maxOf(0, roi.left), maxOf(0, roi.top), minOf(screenWidth, roi.right), minOf(screenHeight, roi.bottom)),
                        region.tapLeadMs) ?: return@mapNotNull null
                    if (overlapsExecutionOverlay(point.x, point.y)) return@mapNotNull null
                    hit to PointF(point.x, point.y)
                }.distinctBy { (_, point) -> point.x.toInt() / 12 to point.y.toInt() / 12 }
                if (taps.isEmpty()) return@withContext emptyList<MatchResultNative>()
                Log.i(TAG, "Moving tap: tracks=${taps.map { it.first.trackId }}, acquired-frame-age=${SystemClock.uptimeMillis() - observedAt}ms, points=${taps.map { it.second }}")
                val accepted = duringGesture { VisionActionExecutor.executeMultiTap(taps.map { it.second }, durationMs = 32) }
                Log.i(TAG, "Moving gesture completed=$accepted (game hit is not confirmed by Android)")
                taps.map { it.first }
            }
            val completedAt = SystemClock.uptimeMillis()
            dispatched.forEach {
                val key = it.id to it.trackId
                movingAttempts[key] = completedAt to ((movingAttempts[key]?.second ?: 0) + 1)
            }
        }
    }

    private var currentStepIndex = 0
    private var lastActionTime = 0L
    private var stepStuckSince = 0L              // When current step first failed to match
    private var fullscreenFallbackRequested = false  // Trigger native full-screen search for stuck step
    private val detectActiveMatchKeys = mutableSetOf<DetectMatchKey>()
    private val detectMissCounts = mutableMapOf<DetectMatchKey, Int>()
    private val detectLastActionAt = mutableMapOf<DetectMatchKey, Long>()

    private data class DetectMatchKey(
        val regionId: Int,
        val bucketX: Int,
        val bucketY: Int
    )

    private data class PendingDetectAction(
        val region: VisionRegion,
        val centerX: Int,
        val centerY: Int,
        val key: DetectMatchKey
    )

    private fun resetExecutionState() {
        currentStepIndex = 0
        lastActionTime = 0L
        stepStuckSince = 0L
        fullscreenFallbackRequested = false
        resetDetectOnlyState()
    }

    private fun resetDetectOnlyState() {
        detectActiveMatchKeys.clear()
        detectMissCounts.clear()
        detectLastActionAt.clear()
    }

    private suspend fun handleDetectOnlyExecution(
        preset: VisionPreset,
        results: Array<MatchResultNative>,
        observedAt: Long,
        generation: Int
    ) {
        val now = SystemClock.uptimeMillis()
        val regions = preset.regions.associateBy { it.id }
        val seen = mutableSetOf<DetectMatchKey>()
        val pending = results.filter { it.matched }.mapNotNull { hit ->
            val region = regions[hit.id] ?: return@mapNotNull null
            val x = hit.x + hit.width / 2
            val y = hit.y + hit.height / 2
            val key = detectMatchKey(region, x, y)
            seen.add(key)
            if (key in detectActiveMatchKeys ||
                now - (detectLastActionAt[key] ?: -DETECT_ACTION_COOLDOWN_MS) < DETECT_ACTION_COOLDOWN_MS) {
                return@mapNotNull null
            }
            PendingDetectAction(region, x, y, key)
        }
        updateDetectOnlyPresence(preset, seen)
        if (pending.isEmpty()) return

        val dispatched = withContext(Dispatchers.Main.immediate) {
            if (!canDispatch(generation))
                return@withContext emptyList<PendingDetectAction>()
            val dispatchAt = SystemClock.uptimeMillis()
            if (!isFreshVisionFrame(observedAt, dispatchAt)) {
                if (dispatchAt - lastStaleLogMs >= 2000) {
                    Log.w(TAG, "Vision tap skipped: stale frame age=${dispatchAt - observedAt}ms pending=${pending.size}")
                    lastStaleLogMs = dispatchAt
                }
                return@withContext emptyList<PendingDetectAction>()
            }
            val safe = pending.filterNot {
                val radius = if (it.region.action is VisionAction.Scroll) resources.displayMetrics.heightPixels * 0.25f else 0f
                overlapsExecutionOverlay(it.centerX.toFloat(), it.centerY.toFloat(), radius)
            }.distinctBy { it.centerX / 12 to it.centerY / 12 }
            val first = safe.firstOrNull() ?: return@withContext emptyList<PendingDetectAction>()
            val taps = if (first.region.action is VisionAction.Click) {
                val limit = if (preset.tapDispatchMode == TapDispatchMode.CONCURRENT) VisionActionExecutor.maxConcurrentTapCount() else 1
                safe.filter { it.region.action is VisionAction.Click }.take(limit)
            } else listOf(first)
            Log.i(TAG, "Vision tap: mode=${preset.tapDispatchMode} count=${taps.size} frameAge=${dispatchAt - observedAt}ms points=${taps.map { it.centerX to it.centerY }} deferred=${safe.size - taps.size}")
            val success = duringGesture { if (first.region.action is VisionAction.Click) {
                VisionActionExecutor.executeMultiTap(taps.map { PointF(it.centerX.toFloat(), it.centerY.toFloat()) }, durationMs = 32)
            } else {
                VisionActionExecutor.execute(first.region.action, PointF(first.centerX.toFloat(), first.centerY.toFloat()))
            } }
            Log.i(TAG, "Vision tap result: completed=$success elapsed=${SystemClock.uptimeMillis() - dispatchAt}ms (Android completion, not game-hit confirmation)")
            if (success) taps else emptyList()
        }
        val completedAt = SystemClock.uptimeMillis()
        dispatched.forEach {
            detectLastActionAt[it.key] = completedAt
            detectActiveMatchKeys.add(it.key)
        }
        // Never drain an old frame after a gesture or retry its stale coordinates.
        // Undispatched matches are eligible again on the next captured frame.
    }

    private var lastStaleLogMs = 0L
    private var lastDispatchTimingLogMs = 0L

    private fun detectMatchKey(region: VisionRegion, centerX: Int, centerY: Int): DetectMatchKey {
        val bucketSize = maxOf(
            DETECT_POSITION_BUCKET_PX,
            (minOf(region.width, region.height) / 2).coerceAtLeast(1)
        )
        return DetectMatchKey(
            regionId = region.id,
            bucketX = centerX / bucketSize,
            bucketY = centerY / bucketSize
        )
    }

    private fun updateDetectOnlyPresence(preset: VisionPreset, seenKeys: Set<DetectMatchKey>) {
        val configuredIds = preset.regions.map { it.id }.toSet()
        val trackedKeys = (detectActiveMatchKeys + detectMissCounts.keys + detectLastActionAt.keys).toSet()

        trackedKeys.forEach { key ->
            if (key.regionId !in configuredIds) {
                clearDetectMatchKey(key)
                return@forEach
            }

            if (key in seenKeys) {
                detectMissCounts[key] = 0
            } else {
                val misses = (detectMissCounts[key] ?: 0) + 1
                detectMissCounts[key] = misses
                if (misses >= DETECT_RELEASE_MISS_FRAMES) {
                    clearDetectMatchKey(key)
                }
            }
        }
    }

    private fun clearDetectMatchKey(key: DetectMatchKey) {
        detectActiveMatchKeys.remove(key)
        detectMissCounts.remove(key)
        detectLastActionAt.remove(key)
    }

    private suspend fun handleSequentialExecution(
        preset: VisionPreset,
        results: Array<com.autonion.automationcompanion.core.vision.MatchResultNative>,
        generation: Int,
        skipOnMiss: Boolean = false
    ) {
        if (System.currentTimeMillis() - lastActionTime < 2000) return

        if (currentStepIndex >= preset.regions.size) {
            currentStepIndex = 0
            stepStuckSince = 0L
            fullscreenFallbackRequested = false
            return
        }

        val targetRegion = preset.regions[currentStepIndex]
        val match = results.find { it.id == targetRegion.id }

        if (match != null && match.matched) {
            Log.d(TAG, "Sequential step $currentStepIndex matched: ID ${targetRegion.id}")
            val success = executeAction(targetRegion, match.x + match.width / 2, match.y + match.height / 2, generation)
            if (success) {
                currentStepIndex++
                lastActionTime = System.currentTimeMillis()
                stepStuckSince = 0L
                fullscreenFallbackRequested = false
            }
        } else if (skipOnMiss) {
            Log.d(TAG, "Sequential step $currentStepIndex not matched: ID ${targetRegion.id}, skipping (Optional Sequential)")
            currentStepIndex++
            lastActionTime = System.currentTimeMillis()
            stepStuckSince = 0L
            fullscreenFallbackRequested = false
        } else {
            // MANDATORY_SEQUENTIAL: step didn't match — track how long it's been stuck
            val now = System.currentTimeMillis()
            if (stepStuckSince == 0L) {
                stepStuckSince = now
            }
            val stuckDuration = now - stepStuckSince

            if (stuckDuration >= STUCK_RESTART_MS) {
                // Phase 2: 30s stuck → restart the entire sequence
                Log.w(TAG, "Step $currentStepIndex (region ID=${targetRegion.id}) stuck for ${stuckDuration}ms — restarting sequence")
                DebugLogger.warning(applicationContext, LogCategory.VISUAL_TRIGGER,
                    "Step Timeout",
                    "Step $currentStepIndex (region ID=${targetRegion.id}) stuck for 30s — restarting sequence from beginning", TAG)
                currentStepIndex = 0
                stepStuckSince = 0L
                fullscreenFallbackRequested = false
            } else if (stuckDuration >= STUCK_FULLSCREEN_FALLBACK_MS && !fullscreenFallbackRequested && targetRegion.customSearchRect() == null) {
                VisionNativeBridge.requestFullscreenSearch(targetRegion.id)
                Log.w(TAG, "Step $currentStepIndex (region ID=${targetRegion.id}) stuck for ${stuckDuration}ms — requested full-screen search")
                DebugLogger.warning(applicationContext, LogCategory.VISUAL_TRIGGER,
                    "Step Stuck",
                    "Step $currentStepIndex (region ID=${targetRegion.id}) hasn't matched for 10s. Full-screen search requested.", TAG)
                fullscreenFallbackRequested = true
            }
        }
    }

    private suspend fun executeAction(region: VisionRegion, screenX: Int, screenY: Int, generation: Int): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (!canDispatch(generation)) return@withContext false
            val point = PointF(screenX.toFloat(), screenY.toFloat())
            val radius = if (region.action is VisionAction.Scroll) resources.displayMetrics.heightPixels * 0.25f else 0f
            if (overlapsExecutionOverlay(point.x, point.y, radius)) return@withContext false
            duringGesture { VisionActionExecutor.execute(region.action, point) }
        }

    private data class CaptureDisplayMetrics(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val rotation: Int
    )

    private fun getRealDisplayMetrics(): CaptureDisplayMetrics {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return CaptureDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.rotation)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying...")
        DebugLogger.info(applicationContext, LogCategory.VISUAL_TRIGGER, "Service Stopped", "VisionExecutionService shutting down", TAG)

        // 1. Pause first so the loop stops calling native match
        isPaused = true
        isRunning = false
        executionGeneration++
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        resizeJob?.cancel()
        detections.close()

        // 2. Stop projection so no more frames arrive
        visionProjection?.stopProjection()
        visionProjection = null

        // 3. Cancel coroutines and wait for them to finish
        job.cancel()

        // 4. Remove overlay
        if (overlayView != null) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            overlayView = null
            overlayLayoutParams = null
        }

        // 5. Release native resources after delay (let any in-flight JNI call finish)
        //    The C++ side now uses a mutex, so this is safe as long as
        //    the match call finishes before clear is called.
        val nativeGeneration = VisionNativeBridge.templateGeneration()
        Handler(Looper.getMainLooper()).postDelayed({
            try { VisionNativeBridge.release(nativeGeneration) } catch (_: Exception) {}
            Log.d(TAG, "Native resources released")
        }, 1000)
    }
}
