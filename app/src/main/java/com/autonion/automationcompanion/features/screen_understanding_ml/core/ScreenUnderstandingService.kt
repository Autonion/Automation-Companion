package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.ActionExecutor
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ExecutionMode
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ScopeType
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import com.autonion.automationcompanion.features.screen_understanding_ml.ui.CaptureEditorActivity
import com.autonion.automationcompanion.features.screen_understanding_ml.ui.ScreenAgentOverlay
import com.autonion.automationcompanion.features.screen_understanding_ml.ui.SetupFlowActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import com.autonion.automationcompanion.features.screen_understanding_ml.model.CapturedTextNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ScreenUnderstandingService : Service() {

    companion object {
        private const val TAG = "ScreenUnderstanding"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_understanding_channel"

        /** Static reference so the editor can communicate back */
        @Volatile
        var instance: ScreenUnderstandingService? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjectionCore: MediaProjectionCore? = null
    private var perceptionLayer: PerceptionLayer? = null
    private var temporalTracker: TemporalTracker? = null
    private var overlay: ScreenAgentOverlay? = null
    private var presetRepository: PresetRepository? = null

    fun setOverlayVisibility(visible: Boolean) {
        overlay?.setVisibility(visible)
    }

    // Accumulated steps from multiple snaps
    private val accumulatedSteps: MutableList<AutomationStep> = mutableListOf()
    // Tracks the preset ID across saves so subsequent saves update the same preset
    private var savedPresetId: String? = null

    @Volatile
    private var latestElements: List<UIElement> = emptyList()
    @Volatile
    private var latestBitmap: Bitmap? = null
    @Volatile
    private var isPlaying = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    var currentPresetId: String? = null
        private set
    
    // Flow mode state
    private var isFlowMode = false
    private var flowNodeId: String? = null
    private var flowMlJson: String? = null
    private var clearOnStart: Boolean = false
    private var flowImagePath: String? = null
    private var flowEditorMode: String? = null
    private var isA11yOnlyMode = false

    // Debug metrics mode
    var isDebugMode = false
        set(value) {
            field = value
            overlay?.debugMode = value
        }

    private fun readDeviceTemperature(): Float {
        // Try thermal zone files (CPU temperature)
        try {
            for (i in 0..15) {
                val file = java.io.File("/sys/class/thermal/thermal_zone$i/temp")
                if (file.exists() && file.canRead()) {
                    val rawTemp = file.readText().trim().toFloatOrNull() ?: continue
                    val temp = if (rawTemp > 1000) rawTemp / 1000f else rawTemp
                    if (temp in 10f..120f) return temp // Sanity check
                }
            }
        } catch (_: Exception) {}

        // Fallback: battery temperature via BatteryManager
        try {
            val batteryIntent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val batteryTemp = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (batteryTemp > 0) return batteryTemp / 10f // Battery temp is in tenths of °C
        } catch (_: Exception) {}

        return -1f
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate - Instance Created: $this")
        instance = this
        presetRepository = PresetRepository(this)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy - Instance Destroyed: $this")

        // Log final inference stats to debugger
        val avgMs = perceptionLayer?.getAverageInferenceTimeMs() ?: 0f
        val count = perceptionLayer?.getInferenceCount() ?: 0
        if (count > 0) {
            DebugLogger.info(
                this, LogCategory.UI_RECOGNITION_AI,
                "Session ended",
                "Processed $count frames, avg inference: ${"%.1f".format(avgMs)}ms/frame",
                TAG
            )
        }

        instance = null
        isPlaying = false
        scope.cancel()
        overlay?.dismiss()
        mediaProjectionCore?.stopProjection()
        perceptionLayer?.close()
        super.onDestroy()
    }

    // ... (onStartCommand remains same)

    /** Called by CaptureEditorActivity to add selected elements to the accumulated preset */
    fun addStepsFromEditor(steps: List<AutomationStep>) {
        Log.d(TAG, "addStepsFromEditor called with ${steps.size} steps. Current total: ${accumulatedSteps.size}")
        accumulatedSteps.addAll(steps)
        // Re-index all steps sequentially
        accumulatedSteps.forEachIndexed { index, step ->
            step.orderIndex = index
        }
        Log.d(TAG, "Steps added. New total: ${accumulatedSteps.size}")
        Toast.makeText(this, "Added ${steps.size} elements (Total: ${accumulatedSteps.size})", Toast.LENGTH_SHORT).show()
        // Reveal save button now that we have captured content
        overlay?.showSaveButton()
        // Clear flowMlJson after the first accumulation so subsequent snaps
        // don't re-load stale selections in the editor.
        if (isFlowMode) {
            flowMlJson = null
            clearOnStart = false
        }
    }

    /** Called by CaptureEditorActivity to store the latest image and editor mode for flow broadcast */
    fun updateFlowMetadata(imagePath: String?, editorMode: String?) {
        if (imagePath != null) flowImagePath = imagePath
        if (editorMode != null) flowEditorMode = editorMode
    }

    /** Save all accumulated steps as a preset */
    private fun saveAccumulatedPreset(name: String) {
        val normalizedName = name.trim()
        Log.d(TAG, "saveAccumulatedPreset called. Name: $normalizedName, Count: ${accumulatedSteps.size}")
        if (normalizedName.isEmpty()) {
            Toast.makeText(this, "Preset name is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (accumulatedSteps.isEmpty()) {
            Toast.makeText(this, "No elements to save (Count: 0) — snap and select first", Toast.LENGTH_SHORT).show()
            return
        }
        if (presetRepository?.hasPresetNamed(normalizedName, excludingId = savedPresetId) == true) {
            Toast.makeText(this, "A preset with this name already exists", Toast.LENGTH_SHORT).show()
            return
        }

        // Reuse the same preset ID within a session so repeated saves update
        // the same file instead of creating duplicates
        val presetId = savedPresetId ?: UUID.randomUUID().toString()
        savedPresetId = presetId

        val preset = AutomationPreset(
            id = presetId,
            name = normalizedName,
            scope = ScopeType.GLOBAL,
            executionMode = ExecutionMode.STRICT,
            steps = accumulatedSteps.toList()
        )
        presetRepository?.savePreset(preset)
        Toast.makeText(this, "Preset '$normalizedName' saved with ${accumulatedSteps.size} steps!", Toast.LENGTH_LONG).show()
    }

    /**
     * Flow mode: Broadcast all accumulated steps back to FlowEditorViewModel
     * via LocalBroadcast, then stop the service (overlay + MediaProjection).
     */
    private fun saveAccumulatedStepsForFlow() {
        if (accumulatedSteps.isEmpty()) {
            Toast.makeText(this, "No elements captured — snap and select first", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val json = Json.encodeToString(accumulatedSteps.toList())
            val tempFile = java.io.File(cacheDir, "flow_ml_${flowNodeId}.json")
            tempFile.writeText(json)

            val resultIntent = Intent(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.ACTION_FLOW_ML_DONE).apply {
                putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_NODE_ID, flowNodeId)
                putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_FILE_PATH, tempFile.absolutePath)
                putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_IMAGE_PATH, flowImagePath)
                putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_ML_MODE, flowEditorMode ?: "ELEMENTS")
            }
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this).sendBroadcast(resultIntent)

            Toast.makeText(this, "Flow node configured with ${accumulatedSteps.size} steps", Toast.LENGTH_SHORT).show()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save flow steps", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Determine foreground service type BEFORE starting notification
        val isA11yOnlyAction = intent?.action == "START_CAPTURE_A11Y_ONLY"
        startForegroundNotification(useMediaProjectionType = !isA11yOnlyAction)

        when (intent?.action) {
            "START_CAPTURE" -> handleStartCapture(intent)
            "START_CAPTURE_A11Y_ONLY" -> handleStartCaptureA11yOnly(intent)
            "DEBUG_TOGGLE" -> {
                isDebugMode = !isDebugMode
                Log.d(TAG, "Debug mode toggled: $isDebugMode")
                Toast.makeText(this, "Debug Mode: ${if (isDebugMode) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}, stopping")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(useMediaProjectionType: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Understanding",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Agent Active")
            .setContentText("Understanding screen content...")
            .setSmallIcon(com.autonion.automationcompanion.R.drawable.ic_notification)
            .build()

        if (useMediaProjectionType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun handleStartCaptureA11yOnly(intent: Intent) {
        val presetName = intent.getStringExtra("presetName")
        val playPresetId = intent.getStringExtra("playPresetId")

        isFlowMode = intent.getBooleanExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_MODE, false)
        flowNodeId = intent.getStringExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_NODE_ID)
        flowMlJson = intent.getStringExtra("EXTRA_FLOW_ML_JSON")
        clearOnStart = intent.getBooleanExtra("EXTRA_CLEAR_ON_START", false)
        isA11yOnlyMode = true

        accumulatedSteps.clear()
        savedPresetId = null

        overlay?.dismiss()
        mediaProjectionCore?.stopProjection()
        perceptionLayer?.close()

        val presetToPlay = if (playPresetId != null) {
            presetRepository?.getPreset(playPresetId)
        } else null

        overlay = ScreenAgentOverlay(
            context = this,
            initialName = if (isFlowMode) "Flow" else (presetToPlay?.name ?: presetName),
            onAnchorSelected = { /* No-op in capture mode */ },
            onSave = { name, _ ->
                if (isFlowMode && flowNodeId != null) {
                    saveAccumulatedStepsForFlow()
                } else {
                    saveAccumulatedPreset(name)
                }
            },
            onPlay = { _, _ ->
                if (presetToPlay != null) {
                    playPreset(presetToPlay)
                }
            },
            onCapture = { captureA11ySnapshot() },
            onPausePlayback = { stopPlayback() },
            onStop = { stopSelf() }
        )

        if (android.provider.Settings.canDrawOverlays(this)) {
            overlay?.showCaptureMode()
        }
    }

    private fun handleStartCapture(intent: Intent) {
        val resultCode = intent.getIntExtra("resultCode", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        val presetName = intent.getStringExtra("presetName")
        val playPresetId = intent.getStringExtra("playPresetId")
        val modelFile = intent.getStringExtra("modelFile")
        
        isFlowMode = intent.getBooleanExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_MODE, false)
        flowNodeId = intent.getStringExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_NODE_ID)
        flowMlJson = intent.getStringExtra("EXTRA_FLOW_ML_JSON")
        val flowNodeMode = intent.getStringExtra("EXTRA_FLOW_NODE_MODE")
        isA11yOnlyMode = (flowNodeMode == "UI_ATTRIBUTE")
        clearOnStart = intent.getBooleanExtra("EXTRA_CLEAR_ON_START", false)

        Log.d(TAG, "Service received presetName: '$presetName', playPresetId: '$playPresetId', modelFile: '$modelFile'")

        // Check for debug mode flag
        if (intent.getBooleanExtra("debugMode", false)) {
            isDebugMode = true
        }

        if (resultCode != 0 && data != null) {
            // Store preset info before startCapture so overlay mode is correct
            if (playPresetId != null) {
                currentPresetId = playPresetId
            }
            startCapture(resultCode, data, presetName, playPresetId, modelFile)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent, presetName: String?, playPresetId: String?, modelFile: String? = null) {
        // Cleanup existing resources
        overlay?.dismiss()
        mediaProjectionCore?.stopProjection()
        perceptionLayer?.close()

        val (realWidth, realHeight, densityDpi) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = windowManager.currentWindowMetrics.bounds
            val density = resources.configuration.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Triple(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi)
        }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionCore = MediaProjectionCore(this, mediaProjectionManager!!) {
            // MediaProjection was revoked by the OS (screen off, app closed, etc.)
            Log.w(TAG, "MediaProjection lost — stopping service")
            DebugLogger.warning(
                this@ScreenUnderstandingService, LogCategory.UI_RECOGNITION_AI,
                "Screen capture lost",
                "MediaProjection revoked by the system — restart required",
                TAG
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@ScreenUnderstandingService, "Screen capture lost — please restart", Toast.LENGTH_LONG).show()
            }
            stopSelf()
        }
        perceptionLayer = PerceptionLayer(this, modelFile)
        temporalTracker = TemporalTracker()

        // Load preset if in playback mode
        val presetToPlay = if (playPresetId != null) {
            presetRepository?.getPreset(playPresetId)
        } else null

        // Clear accumulated steps for new capture session
        accumulatedSteps.clear()
        savedPresetId = null

        overlay = ScreenAgentOverlay(
            context = this,
            initialName = if (isFlowMode) "Flow" else (presetToPlay?.name ?: presetName),
            onAnchorSelected = { /* No-op in capture mode */ },
            onSave = { name, _ ->
                if (isFlowMode && flowNodeId != null) {
                    // Flow mode: broadcast accumulated steps to FlowEditorViewModel
                    saveAccumulatedStepsForFlow()
                } else {
                    // Normal mode: save as preset
                    saveAccumulatedPreset(name)
                }
            },
            onPlay = { _, _ ->
                // Triggered when user taps Play on overlay in playback mode
                if (presetToPlay != null) {
                    playPreset(presetToPlay)
                }
            },
            onCapture = { captureSnapshot() },
            onPausePlayback = { stopPlayback() },
            onStop = { stopSelf() }
        )

        if (android.provider.Settings.canDrawOverlays(this)) {
            if (isDebugMode) {
                // Debug/test mode: live bounding boxes + metrics HUD, no capture controls
                overlay?.showDebugMode()
                Toast.makeText(this, "Debug Mode: Live detection active", Toast.LENGTH_LONG).show()
            } else if (playPresetId != null) {
                // Playback mode: show Play + Stop buttons
                overlay?.showPlaybackMode()
                Toast.makeText(this, "Navigate to the app, then tap Play ▶", Toast.LENGTH_LONG).show()
            } else {
                // Capture mode: show Snap + Close buttons
                overlay?.showCaptureMode()
            }
        }

        mediaProjectionCore?.startProjection(resultCode, data, realWidth, realHeight, densityDpi)

        scope.launch {
            var frameCount = 0L
            var lastFpsTime = android.os.SystemClock.elapsedRealtime()
            var framesInWindow = 0
            var currentFps = 0f

            mediaProjectionCore?.screenCaptureFlow?.collect { bitmap ->
                frameCount++
                framesInWindow++

                // Calculate FPS every second
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFpsTime >= 1000) {
                    currentFps = framesInWindow * 1000f / (now - lastFpsTime)
                    framesInWindow = 0
                    lastFpsTime = now
                }

                // Store a copy of the latest bitmap for snap capture (always)
                latestBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

                // Frame skipping: run detection every 2nd frame in normal mode, every frame in debug mode
                val shouldDetect = if (isDebugMode) true else (frameCount % 2 == 1L)
                if (shouldDetect) {
                    val detections = perceptionLayer?.detectWithAccessibilityAugmentation(bitmap) ?: emptyList()
                    val tracked = temporalTracker?.update(detections) ?: emptyList()
                    latestElements = tracked

                    withContext(Dispatchers.Main) {
                        overlay?.updateElements(tracked)
                    }
                }

                // Update debug metrics overlay
                if (isDebugMode) {
                    val debugMetrics = com.autonion.automationcompanion.features.screen_understanding_ml.ui.DebugMetrics(
                        fps = currentFps,
                        inferenceMs = perceptionLayer?.getLastInferenceTimeMs() ?: 0f,
                        avgInferenceMs = perceptionLayer?.getAverageInferenceTimeMs() ?: 0f,
                        elementCount = latestElements.size,
                        a11yElementCount = latestElements.count { it.source == "accessibility" },
                        temperature = readDeviceTemperature(),
                        delegate = perceptionLayer?.getDelegate() ?: "Unknown",
                        modelName = perceptionLayer?.getModelName() ?: "Unknown",
                        frameCount = frameCount,
                        inferenceCount = perceptionLayer?.getInferenceCount() ?: 0
                    )
                    withContext(Dispatchers.Main) {
                        overlay?.updateMetrics(debugMetrics)
                    }
                }

                // Log stats every 20 frames
                if (frameCount % 20 == 0L) {
                    val avgMs = perceptionLayer?.getAverageInferenceTimeMs() ?: 0f
                    DebugLogger.info(
                        this@ScreenUnderstandingService, LogCategory.UI_RECOGNITION_AI,
                        "Detection stats",
                        "Frame #$frameCount: ${latestElements.size} elements, avg: ${"%.1f".format(avgMs)}ms/frame (skip every 2nd)",
                        TAG
                    )
                }
            }
        }
    }

    private fun captureA11ySnapshot() {
        Log.d(TAG, "A11y-only snap clicked")
        Toast.makeText(this, "Capturing Snapshot...", Toast.LENGTH_SHORT).show()

        scope.launch {
            // 1. Capture interactive elements + text (while target app is in foreground)
            val a11yElements = AccessibilityAugmenter.captureAllInteractiveElements()
            val accTextNodes = captureAccessibilityTextNodes()

            if (a11yElements.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScreenUnderstandingService, "No interactive elements found on screen", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // 2. Take screenshot via AccessibilityService (API 30+) — required for Editor bitmap
            val bitmap = takeAccessibilityScreenshot()
            val file = if (bitmap != null) {
                val f = File(cacheDir, "a11y_capture_${UUID.randomUUID()}.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                f
            } else {
                val metrics = resources.displayMetrics
                val width = metrics.widthPixels.coerceAtLeast(1080)
                val height = metrics.heightPixels.coerceAtLeast(1920)
                val placeholder = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(placeholder)
                canvas.drawColor(android.graphics.Color.DKGRAY)
                val f = File(cacheDir, "a11y_placeholder_${UUID.randomUUID()}.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(f).use { placeholder.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                f
            }

            // 3. Serialize a11y data
            val accElementsJson = try { Json.encodeToString(a11yElements) } catch (_: Exception) { null }
            val accTextJson = try { Json.encodeToString(accTextNodes) } catch (_: Exception) { null }

            // 4. Launch CaptureEditorActivity
            withContext(Dispatchers.Main) {
                val intent = Intent(this@ScreenUnderstandingService, CaptureEditorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("IMAGE_PATH", file.absolutePath)
                    putExtra("PRESET_NAME", overlay?.getCurrentName() ?: "Untitled")
                    putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_MODE, isFlowMode)
                    putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_NODE_ID, flowNodeId)
                    putExtra("A11Y_ONLY_MODE", true)
                    accElementsJson?.let { putExtra("ACC_ELEMENTS_DATA", it) }
                    accTextJson?.let { putExtra("ACC_TEXT_DATA", it) }
                    flowMlJson?.let { putExtra("EXTRA_FLOW_ML_JSON", it) }
                    if (clearOnStart) putExtra("EXTRA_CLEAR_ON_START", true)
                }
                startActivity(intent)
            }
        }
    }

    private suspend fun takeAccessibilityScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "takeAccessibilityScreenshot requires API 30+, current: ${Build.VERSION.SDK_INT}")
            return null
        }

        val service = AccessibilityRouter.getService() ?: run {
            Log.w(TAG, "AccessibilityService not connected — cannot take screenshot")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val hwBitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            result.hardwareBuffer.close()
                            val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hwBitmap?.recycle()
                            continuation.resume(swBitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "Accessibility screenshot failed, errorCode=$errorCode")
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot threw", e)
                continuation.resume(null)
            }
        }
    }

    private fun captureSnapshot() {
        Log.d(TAG, "Snap clicked, latestBitmap=${latestBitmap != null}")
        DebugLogger.info(
            this, LogCategory.UI_RECOGNITION_AI,
            "Snap captured",
            "Screenshot taken for element selection",
            TAG
        )
        scope.launch {
            try {
                // Hide overlay immediately so it doesn't appear in the captured screenshot
                withContext(Dispatchers.Main) {
                    setOverlayVisibility(false)
                }

                // Wait 120ms for WindowManager to remove overlay and VirtualDisplay to receive a clean frame
                delay(120)

                val bitmap = latestBitmap
                if (bitmap != null) {
                    saveBitmapAndOpenEditor(bitmap)
                } else {
                    withContext(Dispatchers.Main) {
                        setOverlayVisibility(true)
                        Toast.makeText(this@ScreenUnderstandingService, "No frame captured yet, wait a moment...", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed during captureSnapshot", e)
                withContext(Dispatchers.Main) {
                    setOverlayVisibility(true)
                }
            }
        }
    }

    private suspend fun saveBitmapAndOpenEditor(bitmap: Bitmap) {
        try {
            val filename = "capture_${UUID.randomUUID()}.png"
            val file = File(cacheDir, filename)
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            // Pre-capture accessibility data WHILE the target app is still in the foreground.
            // Once the Editor opens, rootInActiveWindow will point to the Editor, not the target.
            val accTextNodes = captureAccessibilityTextNodes()
            val accTextJson = if (accTextNodes.isNotEmpty()) {
                try {
                    Json.encodeToString(accTextNodes)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to serialize acc text nodes", e)
                    null
                }
            } else null
            Log.d(TAG, "Pre-captured ${accTextNodes.size} accessibility text nodes for editor")

            // Pre-capture interactive accessibility elements for augmenting YOLO in the editor
            val accInteractiveElements = AccessibilityAugmenter.captureAllInteractiveElements()
            val accElementsJson = if (accInteractiveElements.isNotEmpty()) {
                try {
                    Json.encodeToString(accInteractiveElements)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to serialize acc interactive elements", e)
                    null
                }
            } else null
            Log.d(TAG, "Pre-captured ${accInteractiveElements.size} interactive accessibility elements for editor")

            withContext(Dispatchers.Main) {
                // Don't stopSelf — service stays alive for multi-snap
                val intent = Intent(this@ScreenUnderstandingService, CaptureEditorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("IMAGE_PATH", file.absolutePath)
                    putExtra("PRESET_NAME", overlay?.getCurrentName() ?: "Untitled")
                    putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_MODE, isFlowMode)
                    putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_NODE_ID, flowNodeId)
                    putExtra("A11Y_ONLY_MODE", isA11yOnlyMode)
                    flowMlJson?.let { putExtra("EXTRA_FLOW_ML_JSON", it) }
                    if (clearOnStart) putExtra("EXTRA_CLEAR_ON_START", true)
                    accTextJson?.let { putExtra("ACC_TEXT_DATA", it) }
                    accElementsJson?.let { putExtra("ACC_ELEMENTS_DATA", it) }
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot", e)
        }
    }

    /**
     * Capture all text nodes from the accessibility tree while the target app is in the foreground.
     * This must be called BEFORE opening the CaptureEditorActivity.
     */
    private fun captureAccessibilityTextNodes(): List<CapturedTextNode> {
        try {
            val service = AccessibilityRouter.getService() ?: return emptyList()
            val root = try { service.rootInActiveWindow } catch (_: Exception) { null } ?: return emptyList()
            val nodes = mutableListOf<CapturedTextNode>()
            try {
                collectTextNodes(root, nodes, depth = 0)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
            Log.d(TAG, "Captured ${nodes.size} text nodes from accessibility tree")
            return nodes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to capture accessibility text: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Recursively collect all text/contentDescription nodes from the accessibility tree.
     */
    private fun collectTextNodes(
        node: android.view.accessibility.AccessibilityNodeInfo,
        result: MutableList<CapturedTextNode>,
        depth: Int
    ) {
        if (depth > 15) return
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString() ?: node.contentDescription?.toString()
        if (!text.isNullOrBlank() && bounds.width() > 0 && bounds.height() > 0) {
            result.add(CapturedTextNode(
                text = text,
                boundsLeft = bounds.left.toFloat(),
                boundsTop = bounds.top.toFloat(),
                boundsRight = bounds.right.toFloat(),
                boundsBottom = bounds.bottom.toFloat()
            ))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectTextNodes(child, result, depth + 1)
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
    }



    private fun stopPlayback() {
        if (isPlaying) {
            isPlaying = false
            Toast.makeText(this, "Playback Paused", Toast.LENGTH_SHORT).show()
            overlay?.setPlaybackState(false)
        }
    }

    fun playPreset(preset: AutomationPreset) {
    if (isPlaying) {
        Toast.makeText(this, "Already playing!", Toast.LENGTH_SHORT).show()
        return
    }
    isPlaying = true
    overlay?.setPlaybackState(true)
    Toast.makeText(this, "Playing: ${preset.name}", Toast.LENGTH_SHORT).show()
    DebugLogger.info(
        this, LogCategory.UI_RECOGNITION_AI,
        "Preset started: ${preset.name}",
        "Playing ${preset.steps.size} steps (mode=${preset.executionMode})",
        "ScreenUnderstandingService"
    )

    scope.launch {
            try {
                // Loop continuously until user clicks Stop
                while (isPlaying) {
                    for (step in preset.steps) {
                        if (!isPlaying) break

                        Log.d(TAG, "Looking for step ${step.orderIndex}: ${step.label}")

                        // OCR text steps: run live OCR to find text at its current position
                        val isOcrStep = step.anchor.label.equals("Text", ignoreCase = true)
                        val foundElement: UIElement? = if (isOcrStep && !step.anchor.text.isNullOrBlank()) {
                            Log.d(TAG, "OCR text step — searching for '${step.anchor.text}' on current screen")
                            findTextOnScreen(step.anchor.text)
                        } else if (isOcrStep) {
                            // No text stored — fall back to saved coordinates
                            Log.d(TAG, "OCR step without text — using saved anchor coords")
                            step.anchor
                        } else {
                            // ML detection step — keep searching via live detection
                            waitForElement(step)
                        }

                        if (!isPlaying) break

                        if (foundElement != null) {
                            val centerX = (foundElement.bounds.left + foundElement.bounds.right) / 2
                            val centerY = (foundElement.bounds.top + foundElement.bounds.bottom) / 2
                            val point = PointF(centerX, centerY)
                            
                            val intent = ActionIntent(
                                type = step.actionType,
                                targetId = step.id,
                                targetPoint = point,
                                inputText = step.inputText,
                                description = step.label
                            )
                            
                            val success = ActionExecutor.execute(this@ScreenUnderstandingService, intent)

                            if (success) {
                            Log.d(TAG, "Executed ${step.actionType} on ${step.label}")
                            DebugLogger.success(
                                this@ScreenUnderstandingService, LogCategory.UI_RECOGNITION_AI,
                                "Step ${step.orderIndex}: ${step.label}",
                                "${step.actionType} executed successfully",
                                "ScreenUnderstandingService"
                            )
                        } else {
                            Log.e(TAG, "Action ${step.actionType} failed for ${step.label}")
                            DebugLogger.error(
                                this@ScreenUnderstandingService, LogCategory.UI_RECOGNITION_AI,
                                "Step ${step.orderIndex} failed: ${step.label}",
                                "${step.actionType} failed — check accessibility",
                                "ScreenUnderstandingService"
                            )
                            withContext(Dispatchers.Main) {
                                    Toast.makeText(this@ScreenUnderstandingService, "Action failed. Accessibility enabled?", Toast.LENGTH_LONG).show()
                                }
                                isPlaying = false
                                break
                            }

                            delay(2000) // Wait after action for screen to settle
                        } else {
                            // Element not found yet — log and keep trying
                            Log.d(TAG, "Step ${step.orderIndex}: ${step.label} not found yet, retrying...")
                            delay(1000)
                        }
                    }

                    if (isPlaying) {
                        // Completed one pass through all steps — wait before restarting
                        Log.d(TAG, "Completed one pass, restarting scan...")
                        delay(2000)
                    }
                }
            } catch (e: Exception) {
            Log.e(TAG, "Playback error", e)
            DebugLogger.error(
                this@ScreenUnderstandingService, LogCategory.UI_RECOGNITION_AI,
                "Playback error: ${preset.name}",
                "${e.javaClass.simpleName}: ${e.message}",
                "ScreenUnderstandingService"
            )
        } finally {
                isPlaying = false
                withContext(Dispatchers.Main) {
                    overlay?.setPlaybackState(false)
                    Toast.makeText(this@ScreenUnderstandingService, "Playback stopped", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Hybrid element matching: combines Accessibility tree + YOLO detection + OCR
     * for robust action targeting, even across screen rotations.
     *
     * Signal weights:
     * - Accessibility (50%): Most reliable when the UI tree is standard
     * - YOLO (30%): Works for custom views, games, non-standard rendering
     * - OCR (20%): Text confirmation to disambiguate similar elements
     *
     * Falls back to YOLO-only IoU matching if hybrid returns no result.
     */
    private suspend fun waitForElement(step: AutomationStep): UIElement? {
        val timeout = 5000L
        val startTime = System.currentTimeMillis()
        val anchorBounds = step.anchor.bounds
        val anchorText = step.anchor.text  // Text from capture-time enrichment (OCR or pre-captured accessibility)

        Log.d(TAG, "waitForElement: step=${step.label}, anchorText=${anchorText ?: "NULL"}, " +
                "bounds=$anchorBounds, captureSize=${step.captureScreenWidth}x${step.captureScreenHeight}")

        // Pre-compute normalized anchor bounds if capture dimensions are available
        val capW = step.captureScreenWidth
        val capH = step.captureScreenHeight
        val normalizedAnchor: RectF? = if (capW > 0f && capH > 0f) {
            RectF(
                anchorBounds.left / capW, anchorBounds.top / capH,
                anchorBounds.right / capW, anchorBounds.bottom / capH
            )
        } else null

        // Detect rotation: compare capture-time image aspect ratio with current screen aspect ratio
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()
        val isRotated = capW > 0f && capH > 0f && screenW > 0f && screenH > 0f &&
                ((capW > capH) != (screenW > screenH))

        if (isRotated) {
            Log.d(TAG, "waitForElement: Rotation detected! capture=${capW}x${capH}, screen=${screenW}x${screenH}")
        }

        while (System.currentTimeMillis() - startTime < timeout && isPlaying) {
            val currentElements = latestElements
            val currentBitmap = latestBitmap
            val curW = currentBitmap?.width?.toFloat() ?: 0f
            val curH = currentBitmap?.height?.toFloat() ?: 0f

            // ── Primary: Hybrid matching (Accessibility + YOLO + OCR) ──
            val hybridResult = HybridElementMatcher.findBestMatch(
                anchorLabel = step.anchor.label,
                anchorBounds = anchorBounds,
                anchorText = anchorText,
                yoloCandidates = currentElements,
                normalizedAnchor = normalizedAnchor,
                currentScreenWidth = curW,
                currentScreenHeight = curH,
                isRotated = isRotated,
                screenWidth = screenW,
                screenHeight = screenH
            )

            if (hybridResult != null) {
                Log.d(TAG, "waitForElement: Hybrid match for '${step.label}': " +
                        "conf=${hybridResult.hybridConfidence} " +
                        "[acc=${hybridResult.accessibilityScore}, " +
                        "yolo=${hybridResult.yoloScore}, " +
                        "ocr=${hybridResult.ocrScore}] " +
                        "via ${hybridResult.source}, rotated=$isRotated")
                DebugLogger.info(
                    this@ScreenUnderstandingService, LogCategory.UI_RECOGNITION_AI,
                    "Hybrid match: ${step.label}",
                    "conf=${"%.2f".format(hybridResult.hybridConfidence)} " +
                            "(acc=${"%.2f".format(hybridResult.accessibilityScore)}, " +
                            "yolo=${"%.2f".format(hybridResult.yoloScore)}, " +
                            "ocr=${"%.2f".format(hybridResult.ocrScore)}) " +
                            "source=${hybridResult.source} rotated=$isRotated",
                    TAG
                )
                return hybridResult.element
            }

            // ── Fallback: YOLO-only matching ──
            val sameLabel = currentElements
                .filter { it.label.equals(step.anchor.label, ignoreCase = true) }

            if (isRotated) {
                // After rotation, IoU is meaningless — match by text + label instead
                if (!anchorText.isNullOrBlank()) {
                    // Try YOLO text match first
                    val textMatch = sameLabel.firstOrNull { el ->
                        HybridElementMatcher.isTextMatching(el.text, anchorText)
                    }
                    if (textMatch != null) {
                        Log.d(TAG, "waitForElement: Rotated YOLO text fallback: label=${textMatch.label}, text=${textMatch.text}")
                        return textMatch
                    }
                    
                    // Direct accessibility query — most reliable for rotated clicks
                    // since accessibility bounds are always in current screen coordinates
                    val accMatch = findAccessibilityElementByText(anchorText, step.anchor.label, screenW, screenH)
                    if (accMatch != null) {
                        Log.d(TAG, "waitForElement: Rotated acc text fallback: label=${accMatch.label}, text=${accMatch.text}, bounds=${accMatch.bounds}")
                        return accMatch
                    }
                } else {
                    // Last resort for rotation: highest-confidence YOLO of matching label
                    // Only accept if we have NO text to match against (truly ambiguous)
                    val bestYolo = sameLabel.maxByOrNull { it.confidence }
                    if (bestYolo != null && bestYolo.confidence > 0.5f) {
                        Log.d(TAG, "waitForElement: Rotated confidence fallback (no text): label=${bestYolo.label}, " +
                                "conf=${bestYolo.confidence}, text=${bestYolo.text}")
                        return bestYolo
                    }
                }
            } else {
                // Normal mode: IoU-based matching
                val useNormalized = normalizedAnchor != null && curW > 0f && curH > 0f

                fun iouFor(el: UIElement): Float {
                    return if (useNormalized) {
                        val nEl = RectF(
                            el.bounds.left / curW, el.bounds.top / curH,
                            el.bounds.right / curW, el.bounds.bottom / curH
                        )
                        calculateIoU(nEl, normalizedAnchor!!)
                    } else {
                        calculateIoU(el.bounds, anchorBounds)
                    }
                }

                val fallbackMatch = if (!anchorText.isNullOrBlank()) {
                    val textMatches = sameLabel.filter { el ->
                        HybridElementMatcher.isTextMatching(el.text, anchorText)
                    }
                    textMatches.maxByOrNull { iouFor(it) }
                    // Strict gating: if anchorText is specified and no candidate matches text, return null
                } else {
                    sameLabel.maxByOrNull { iouFor(it) }
                }

                if (fallbackMatch != null && iouFor(fallbackMatch) > 0.1f) {
                    Log.d(TAG, "waitForElement: YOLO fallback matched: label=${fallbackMatch.label}, " +
                            "text=${fallbackMatch.text}, IoU=${iouFor(fallbackMatch)}, normalized=$useNormalized")
                    return fallbackMatch
                }
            }

            delay(200)
        }
        return null
    }

    /**
     * Query the accessibility tree for an element with the given text and class label.
     * Returns a UIElement with screen-coordinate bounds (correct for clicking after rotation).
     */
    private fun findAccessibilityElementByText(
        text: String, label: String,
        screenWidth: Float, screenHeight: Float
    ): UIElement? {
        try {
            val elements = AccessibilityAugmenter.captureAllInteractiveElements()
            val textMatches = elements.filter { el ->
                // Validate bounds are on screen
                if (el.bounds.right <= 0 || el.bounds.bottom <= 0) return@filter false
                if (screenWidth > 0 && el.bounds.left > screenWidth) return@filter false
                if (screenHeight > 0 && el.bounds.top > screenHeight) return@filter false
                HybridElementMatcher.isTextMatching(el.text, text)
            }
            if (textMatches.isNotEmpty()) {
                return textMatches.maxByOrNull { el ->
                    val classMatch = if (el.label.equals(label, ignoreCase = true)) 0.3f else 0f
                    el.confidence + classMatch
                }
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "findAccessibilityElementByText failed: ${e.message}")
            return null
        }
    }

    /**
     * Run live OCR on the current screen to find where [targetText] appears right now.
     * Returns a UIElement with the text's current bounds, or null if not found within timeout.
     *
     * Search strategy (in order of priority):
     * 1. Exact line-level match within OCR blocks (handles block segmentation differences)
     * 2. Exact block-level match (original behavior)
     * 3. Target text contains a block (reverse containment for smaller blocks)
     * 4. Accessibility tree text search (no OCR needed, uses live a11y nodes)
     */
    private suspend fun findTextOnScreen(targetText: String): UIElement? {
        val timeout = 5000L
        val startTime = System.currentTimeMillis()
        val ocrEngine = OcrEngine()
        val dm = resources.displayMetrics

        try {
            while (System.currentTimeMillis() - startTime < timeout && isPlaying) {
                val bitmap = latestBitmap
                if (bitmap != null) {
                    val result = ocrEngine.recognizeText(bitmap)

                    // ── Strategy 1: Line-level match within blocks ──
                    // ML Kit can segment text differently between runs; searching lines
                    // within blocks handles cases where a block was split or merged.
                    for (block in result.blocks) {
                        for (line in block.lines) {
                            if (HybridElementMatcher.isTextMatching(line.text, targetText)) {
                                val bounds = line.bounds ?: block.bounds
                                if (bounds != null) {
                                    Log.d(TAG, "findTextOnScreen: LINE match '${line.text}' for target '$targetText' at $bounds")
                                    return UIElement(
                                        id = java.util.UUID.randomUUID().toString(),
                                        label = "Text",
                                        confidence = line.confidence ?: block.confidence ?: 0.9f,
                                        bounds = bounds,
                                        text = line.text
                                    )
                                }
                            }
                        }
                    }

                    // ── Strategy 2: Block-level exact match (block contains target) ──
                    val matchBlock = result.blocks.firstOrNull { block ->
                        HybridElementMatcher.isTextMatching(block.text, targetText)
                    }
                    if (matchBlock != null && matchBlock.bounds != null) {
                        Log.d(TAG, "findTextOnScreen: BLOCK match '${matchBlock.text}' for target '$targetText' at ${matchBlock.bounds}")
                        return UIElement(
                            id = java.util.UUID.randomUUID().toString(),
                            label = "Text",
                            confidence = matchBlock.confidence ?: 0.9f,
                            bounds = matchBlock.bounds,
                            text = matchBlock.text
                        )
                    }

                    // ── Strategy 3: Reverse containment (target contains block text) ──
                    // Handles cases where the originally captured block was large but at
                    // runtime it was split into smaller blocks.
                    val reverseMatch = result.blocks.firstOrNull { block ->
                        block.text.length >= 3 && HybridElementMatcher.isTextMatching(targetText, block.text)
                    }
                    if (reverseMatch != null && reverseMatch.bounds != null) {
                        Log.d(TAG, "findTextOnScreen: REVERSE match '${reverseMatch.text}' for target '$targetText' at ${reverseMatch.bounds}")
                        return UIElement(
                            id = java.util.UUID.randomUUID().toString(),
                            label = "Text",
                            confidence = (reverseMatch.confidence ?: 0.9f) * 0.8f,
                            bounds = reverseMatch.bounds,
                            text = reverseMatch.text
                        )
                    }

                    Log.d(TAG, "findTextOnScreen: '$targetText' not found via OCR in ${result.blocks.size} blocks, trying accessibility...")
                }

                // ── Strategy 4: Accessibility tree fallback ──
                // The accessibility tree often has reliable text regardless of OCR accuracy.
                val accMatch = findAccessibilityElementByText(
                    targetText, "Text",
                    dm.widthPixels.toFloat(), dm.heightPixels.toFloat()
                )
                if (accMatch != null) {
                    Log.d(TAG, "findTextOnScreen: A11Y fallback match for '$targetText' at ${accMatch.bounds}")
                    return accMatch
                }

                delay(500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "findTextOnScreen failed", e)
        } finally {
            ocrEngine.close()
        }
        Log.w(TAG, "findTextOnScreen: '$targetText' not found within timeout (tried OCR + accessibility)")
        return null
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        if (interRight < interLeft || interBottom < interTop) return 0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        val unionArea = aArea + bArea - interArea

        return if (unionArea > 0) interArea / unionArea else 0f
    }

    private fun savePreset(name: String, elementsData: List<Pair<UIElement, Boolean>>) {
        val normalizedName = name.trim()
        Log.d(TAG, "savePreset called with ${elementsData.size} elements")
        if (normalizedName.isEmpty()) {
            Toast.makeText(this, "Preset name is required", Toast.LENGTH_SHORT).show()
            return
        }
        if (elementsData.isEmpty()) {
            Log.w(TAG, "Selection list is empty!")
            Toast.makeText(this, "No elements selected to save", Toast.LENGTH_SHORT).show()
            return
        }

        // Enrich elements with accessibility text for robust rotation-aware matching
        val enrichedData = elementsData.map { (element, isOptional) ->
            if (!element.text.isNullOrBlank()) {
                Pair(element, isOptional)
            } else {
                val accText = findAccessibilityTextForElement(element)
                if (accText != null) {
                    Log.d(TAG, "Enriched element '${element.label}' with acc text='$accText'")
                    Pair(element.copy(text = accText), isOptional)
                } else {
                    Pair(element, isOptional)
                }
            }
        }

        val steps = enrichedData.mapIndexed { index, (element, isOptional) ->
            AutomationStep(
                id = UUID.randomUUID().toString(),
                orderIndex = index,
                label = element.label,
                anchor = element,
                isOptional = isOptional,
                captureScreenWidth = latestBitmap?.width?.toFloat() ?: 0f,
                captureScreenHeight = latestBitmap?.height?.toFloat() ?: 0f
            )
        }

        var presetId = UUID.randomUUID().toString()

        if (currentPresetId != null) {
            val existing = presetRepository?.getPreset(currentPresetId!!)
            if (existing != null && existing.name.equals(normalizedName, ignoreCase = true)) {
                presetId = currentPresetId!!
                Log.d(TAG, "Overwriting existing preset: $presetId")
            }
        }

        if (presetRepository?.hasPresetNamed(normalizedName, excludingId = presetId) == true) {
            Toast.makeText(this, "A preset with this name already exists", Toast.LENGTH_SHORT).show()
            return
        }

        val preset = AutomationPreset(
            id = presetId,
            name = normalizedName,
            scope = com.autonion.automationcompanion.features.screen_understanding_ml.model.ScopeType.GLOBAL,
            executionMode = ExecutionMode.STRICT,
            steps = steps
        )

        presetRepository?.savePreset(preset)
        Toast.makeText(this, "Preset '$normalizedName' Saved with ${steps.size} steps!", Toast.LENGTH_LONG).show()
    }

    /**
     * Query the accessibility tree to find text at the given element's screen bounds.
     * Used at save time to enrich YOLO elements with textual identity for rotation-aware matching.
     */
    private fun findAccessibilityTextForElement(element: UIElement): String? {
        try {
            val service = AccessibilityRouter.getService() ?: return null
            val root = try { service.rootInActiveWindow } catch (_: Exception) { null } ?: return null
            try {
                return findTextInAccessibilityNode(root, element.bounds)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Accessibility text enrichment failed: ${e.message}")
            return null
        }
    }

    private fun findTextInAccessibilityNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        targetBounds: RectF,
        depth: Int = 0
    ): String? {
        if (depth > 10) return null
        val nodeBounds = android.graphics.Rect()
        node.getBoundsInScreen(nodeBounds)
        val nodeRect = RectF(nodeBounds)

        if (RectF.intersects(nodeRect, targetBounds)) {
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (!text.isNullOrBlank()) return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findTextInAccessibilityNode(child, targetBounds, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }
}


