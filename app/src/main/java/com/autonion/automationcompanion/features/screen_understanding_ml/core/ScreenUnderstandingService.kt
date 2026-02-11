package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate - Instance Created: $this")
        instance = this
        presetRepository = PresetRepository(this)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy - Instance Destroyed: $this")
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
            .setSmallIcon(R.mipmap.ic_launcher)
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

        Log.d(TAG, "Service received presetName: '$presetName', playPresetId: '$playPresetId'")

        if (resultCode != 0 && data != null) {
            // Store preset info before startCapture so overlay mode is correct
            if (playPresetId != null) {
                currentPresetId = playPresetId
            }
            startCapture(resultCode, data, presetName, playPresetId)
        }
    }

    private fun startCapture(resultCode: Int, data: Intent, presetName: String?, playPresetId: String?) {
        // Cleanup existing resources
        overlay?.dismiss()
        mediaProjectionCore?.stopProjection()
        perceptionLayer?.close()

        val metrics = resources.displayMetrics

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionCore = MediaProjectionCore(this, mediaProjectionManager!!)
        perceptionLayer = PerceptionLayer(this)
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
            if (playPresetId != null) {
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
            mediaProjectionCore?.screenCaptureFlow?.collect { bitmap ->
                Log.d(TAG, "Frame received: ${bitmap.width}x${bitmap.height}")

                // Store a copy of the latest bitmap for snap capture
                latestBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

                val detections = perceptionLayer?.detect(bitmap) ?: emptyList()
                val tracked = temporalTracker?.update(detections) ?: emptyList()

                latestElements = tracked

                withContext(Dispatchers.Main) {
                    overlay?.updateElements(tracked)
                }
            }
        }
    }

    private fun captureSnapshot() {
        Log.d(TAG, "Snap clicked, latestBitmap=${latestBitmap != null}")
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

        scope.launch {
            try {
                // Loop continuously until user clicks Stop
                while (isPlaying) {
                    for (step in preset.steps) {
                        if (!isPlaying) break

                        Log.d(TAG, "Looking for step ${step.orderIndex}: ${step.label}")

                        // Keep searching for this element until found or stopped
                        val foundElement = waitForElement(step)

                        if (!isPlaying) break

                        if (foundElement != null) {
                            val centerX = (foundElement.bounds.left + foundElement.bounds.right) / 2
                            val centerY = (foundElement.bounds.top + foundElement.bounds.bottom) / 2
                            val point = PointF(centerX, centerY)
                            val success = ActionExecutor.executeClick(point)

                            if (success) {
                                Log.d(TAG, "Clicked ${step.label}")
                            } else {
                                Log.e(TAG, "Click failed for ${step.label} - Accessibility Service not connected?")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@ScreenUnderstandingService, "Click failed. Is Accessibility Service enabled?", Toast.LENGTH_LONG).show()
                                }
                                isPlaying = false
                                break
                            }

                            delay(2000) // Wait after click for screen to settle
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
     * B4 fix: Wait for an element matching the step using label + spatial IoU
     * against the saved anchor's bounds, not just the label string.
     */
    private suspend fun waitForElement(step: AutomationStep): UIElement? {
        val timeout = 5000L
        val startTime = System.currentTimeMillis()
        val anchorBounds = step.anchor.bounds

        while (System.currentTimeMillis() - startTime < timeout && isPlaying) {
            val currentElements = latestElements

            // Find best match: same label AND highest IoU with saved anchor bounds
            val match = currentElements
                .filter { it.label == step.anchor.label }  // Use anchor's actual label
                .maxByOrNull { calculateIoU(it.bounds, anchorBounds) }

            // Accept if IoU > 0.1 (lenient since screen may have scrolled slightly)
            if (match != null && calculateIoU(match.bounds, anchorBounds) > 0.1f) {
                return match
            }

            delay(200)
        }
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
                isOptional = isOptional
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


