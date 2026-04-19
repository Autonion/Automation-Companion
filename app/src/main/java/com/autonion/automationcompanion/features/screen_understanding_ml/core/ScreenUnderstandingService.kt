package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
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

    @Volatile
    private var latestElements: List<UIElement> = emptyList()
    @Volatile
    private var latestBitmap: Bitmap? = null
    @Volatile
    private var isPlaying = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentPresetId: String? = null
    
    // Flow mode state
    private var isFlowMode = false
    private var flowNodeId: String? = null
    private var flowMlJson: String? = null
    private var clearOnStart: Boolean = false

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
                this, LogCategory.SCREEN_CONTEXT_AI,
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
    }

    /** Save all accumulated steps as a preset */
    private fun saveAccumulatedPreset(name: String) {
        Log.d(TAG, "saveAccumulatedPreset called. Name: $name, Count: ${accumulatedSteps.size}")
        if (accumulatedSteps.isEmpty()) {
            Toast.makeText(this, "No elements to save (Count: 0) — snap and select first", Toast.LENGTH_SHORT).show()
            return
        }
        val preset = AutomationPreset(
            id = UUID.randomUUID().toString(),
            name = name,
            scope = ScopeType.GLOBAL,
            executionMode = ExecutionMode.STRICT,
            steps = accumulatedSteps.toList()
        )
        presetRepository?.savePreset(preset)
        Toast.makeText(this, "Preset '$name' saved with ${accumulatedSteps.size} steps!", Toast.LENGTH_LONG).show()
        accumulatedSteps.clear()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always use media projection FGS type — all paths go through SetupFlowActivity first
        startForegroundNotification(useMediaProjectionType = true)

        when (intent?.action) {
            "START_CAPTURE" -> handleStartCapture(intent)
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
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

        val metrics = resources.displayMetrics

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionCore = MediaProjectionCore(this, mediaProjectionManager!!)
        perceptionLayer = PerceptionLayer(this, modelFile)
        temporalTracker = TemporalTracker()

        // Load preset if in playback mode
        val presetToPlay = if (playPresetId != null) {
            presetRepository?.getPreset(playPresetId)
        } else null

        // Clear accumulated steps for new capture session
        accumulatedSteps.clear()

        overlay = ScreenAgentOverlay(
            context = this,
            initialName = presetToPlay?.name ?: presetName,
            onAnchorSelected = { /* No-op in capture mode */ },
            onSave = { name, _ ->
                // Save accumulated steps as preset
                saveAccumulatedPreset(name)
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

        mediaProjectionCore?.startProjection(resultCode, data, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

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
                    val detections = perceptionLayer?.detect(bitmap) ?: emptyList()
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
                        this@ScreenUnderstandingService, LogCategory.SCREEN_CONTEXT_AI,
                        "Detection stats",
                        "Frame #$frameCount: ${latestElements.size} elements, avg: ${"%.1f".format(avgMs)}ms/frame (skip every 2nd)",
                        TAG
                    )
                }
            }
        }
    }

    private fun captureSnapshot() {
        Log.d(TAG, "Snap clicked, latestBitmap=${latestBitmap != null}")
        DebugLogger.info(
            this, LogCategory.SCREEN_CONTEXT_AI,
            "Snap captured",
            "Screenshot taken for element selection",
            TAG
        )
        val bitmap = latestBitmap
        if (bitmap != null) {
            Toast.makeText(this, "Capturing Snapshot...", Toast.LENGTH_SHORT).show()
            scope.launch { saveBitmapAndOpenEditor(bitmap) }
        } else {
            Toast.makeText(this, "No frame captured yet, wait a moment...", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun saveBitmapAndOpenEditor(bitmap: Bitmap) {
        try {
            val filename = "capture_${UUID.randomUUID()}.png"
            val file = File(cacheDir, filename)
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            withContext(Dispatchers.Main) {
                // Don't stopSelf — service stays alive for multi-snap
                val intent = Intent(this@ScreenUnderstandingService, CaptureEditorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("IMAGE_PATH", file.absolutePath)
                    putExtra("PRESET_NAME", overlay?.getCurrentName() ?: "Untitled")
                    putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_MODE, isFlowMode)
                    putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_NODE_ID, flowNodeId)
                    flowMlJson?.let { putExtra("EXTRA_FLOW_ML_JSON", it) }
                    if (clearOnStart) putExtra("EXTRA_CLEAR_ON_START", true)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot", e)
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
        this, LogCategory.SCREEN_CONTEXT_AI,
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
                                this@ScreenUnderstandingService, LogCategory.SCREEN_CONTEXT_AI,
                                "Step ${step.orderIndex}: ${step.label}",
                                "${step.actionType} executed successfully",
                                "ScreenUnderstandingService"
                            )
                        } else {
                            Log.e(TAG, "Action ${step.actionType} failed for ${step.label}")
                            DebugLogger.error(
                                this@ScreenUnderstandingService, LogCategory.SCREEN_CONTEXT_AI,
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
                this@ScreenUnderstandingService, LogCategory.SCREEN_CONTEXT_AI,
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
        val anchorText = step.anchor.text  // OCR text from capture-time enrichment

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
                    this@ScreenUnderstandingService, LogCategory.SCREEN_CONTEXT_AI,
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
                    val textMatch = sameLabel.firstOrNull { el ->
                        !el.text.isNullOrBlank() && el.text.contains(anchorText, ignoreCase = true)
                    }
                    if (textMatch != null) {
                        Log.d(TAG, "waitForElement: Rotated text fallback: label=${textMatch.label}, text=${textMatch.text}")
                        // YOLO bounds are in image space — but after rotation image≠screen.
                        // Return as-is; ActionExecutor will click at these coords which may be image-space.
                        // Ideally we'd convert, but accessibility match above should handle most cases.
                        return textMatch
                    }
                }
                // Last resort for rotation: highest-confidence YOLO of matching label
                val bestYolo = sameLabel.maxByOrNull { it.confidence }
                if (bestYolo != null && bestYolo.confidence > 0.5f) {
                    Log.d(TAG, "waitForElement: Rotated confidence fallback: label=${bestYolo.label}, " +
                            "conf=${bestYolo.confidence}, text=${bestYolo.text}")
                    return bestYolo
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
                        !el.text.isNullOrBlank() && el.text.contains(anchorText, ignoreCase = true)
                    }
                    textMatches.maxByOrNull { iouFor(it) }
                        ?: sameLabel.maxByOrNull { iouFor(it) }
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
     * Run live OCR on the current screen to find where [targetText] appears right now.
     * Returns a UIElement with the text's current bounds, or null if not found within timeout.
     */
    private suspend fun findTextOnScreen(targetText: String): UIElement? {
        val timeout = 5000L
        val startTime = System.currentTimeMillis()
        val ocrEngine = OcrEngine()

        try {
            while (System.currentTimeMillis() - startTime < timeout && isPlaying) {
                val bitmap = latestBitmap
                if (bitmap != null) {
                    val result = ocrEngine.recognizeText(bitmap)
                    // Find the best matching block (case-insensitive contains)
                    val matchBlock = result.blocks.firstOrNull { block ->
                        block.text.contains(targetText, ignoreCase = true)
                    }
                    if (matchBlock != null && matchBlock.bounds != null) {
                        Log.d(TAG, "findTextOnScreen: found '${matchBlock.text}' at ${matchBlock.bounds}")
                        return UIElement(
                            id = java.util.UUID.randomUUID().toString(),
                            label = "Text",
                            confidence = matchBlock.confidence ?: 0.9f,
                            bounds = matchBlock.bounds,
                            text = matchBlock.text
                        )
                    }
                    Log.d(TAG, "findTextOnScreen: '$targetText' not found in ${result.blocks.size} blocks, retrying...")
                }
                delay(500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "findTextOnScreen failed", e)
        } finally {
            ocrEngine.close()
        }
        Log.w(TAG, "findTextOnScreen: '$targetText' not found within timeout")
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
        Log.d(TAG, "savePreset called with ${elementsData.size} elements")
        if (elementsData.isEmpty()) {
            Log.w(TAG, "Selection list is empty!")
            Toast.makeText(this, "No elements selected to save", Toast.LENGTH_SHORT).show()
            return
        }

        val steps = elementsData.mapIndexed { index, (element, isOptional) ->
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
            if (existing != null && existing.name == name) {
                presetId = currentPresetId!!
                Log.d(TAG, "Overwriting existing preset: $presetId")
            }
        }

        val preset = AutomationPreset(
            id = presetId,
            name = name,
            scope = com.autonion.automationcompanion.features.screen_understanding_ml.model.ScopeType.GLOBAL,
            executionMode = ExecutionMode.STRICT,
            steps = steps
        )

        presetRepository?.savePreset(preset)
        Toast.makeText(this, "Preset '$name' Saved with ${steps.size} steps!", Toast.LENGTH_LONG).show()
    }
}


