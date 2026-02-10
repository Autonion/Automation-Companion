package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.screen_understanding_ml.ui.ScreenAgentOverlay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScreenUnderstandingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // startForegroundService() // Moved to onStartCommand to ensure permission readiness
        presetRepository = com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository(this)
    }

    private fun startForegroundService() {
        val channelId = "screen_understanding_channel"
        val channelName = "Screen Undestanding"
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Agent Active")
            .setContentText("Understanding screen content...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1001, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(1001, notification)
        }
    }

    private var mediaProjectionManager: android.media.projection.MediaProjectionManager? = null
    private var mediaProjectionCore: MediaProjectionCore? = null
    private var perceptionLayer: PerceptionLayer? = null
    private var temporalTracker: TemporalTracker? = null
    private var overlay: ScreenAgentOverlay? = null
    private var presetRepository: com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository? = null
    private var latestElements: List<com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement> = emptyList()
    private var isPlaying = false

    
    // Coroutine scope
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_CAPTURE") {
            // Move startForeground here to ensure we have permission context or are about to start
            // Actually, for MediaProjection type, it must be called *after* we have the result BUT
            // it must be called within 5 seconds of service start. 
            // Valid place is here.
             
            startForegroundService()
            
            val resultCode = intent.getIntExtra("resultCode", 0)
            val data = intent.getParcelableExtra<Intent>("data")
            val presetName = intent.getStringExtra("presetName")
            val playPresetId = intent.getStringExtra("playPresetId")
            
            if (resultCode != 0 && data != null) {
                startCapture(resultCode, data, presetName)
                
                // If we were asked to play a preset after starting
                if (playPresetId != null) {
                    val preset = presetRepository?.getPreset(playPresetId)
                    if (preset != null) {
                        playPreset(preset)
                    }
                }
            }
        } else if (intent?.action == "PLAY_PRESET") {
            val presetId = intent.getStringExtra("presetId")
            if (presetId != null) {
                val preset = presetRepository?.getPreset(presetId)
                if (preset != null) {
                    if (mediaProjectionCore == null) {
                         // Service not running or no projection. Request permission via Activity.
                         android.util.Log.d("ScreenUnderstanding", "Requesting permission for playback")
                         
                         val permIntent = Intent(this, com.autonion.automationcompanion.features.screen_understanding_ml.ui.SetupFlowActivity::class.java).apply {
                             addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                             putExtra("ACTION_REQUEST_PERMISSION_PLAY_PRESET", presetId)
                         }
                         startActivity(permIntent)
                         
                    } else {
                        playPreset(preset)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent, presetName: String?) {
        // Cleanup existing resources if any
        // ... (cleanup code)
        
        overlay?.dismiss()
        mediaProjectionCore?.stopProjection()
        perceptionLayer?.close()
        
        // Re-initialize resources...
        
        val metrics = resources.displayMetrics
        
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        mediaProjectionCore = MediaProjectionCore(this, mediaProjectionManager!!)
        perceptionLayer = PerceptionLayer(this)
        temporalTracker = TemporalTracker()
        overlay = ScreenAgentOverlay(
            context = this,
            initialName = presetName, // Pass to overlay
            onAnchorSelected = { element ->
                // Handle selection 
                android.widget.Toast.makeText(this, "Toggled: ${element.label}", android.widget.Toast.LENGTH_SHORT).show()
            },
            onSave = { name, selectedElements ->
                savePreset(name, selectedElements)
                overlay?.dismiss()
                stopSelf()
            },
            onPlay = {
                // Play currently selected elements as a temporary preset
                val elements = overlay?.getSelectedElements() ?: emptyList()
                val configs = overlay?.getSelectionConfig() ?: emptyList()
                
                if (elements.isNotEmpty()) {
                    val steps = elements.zip(configs).mapIndexed { index, (element, isOptional) ->
                         com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep(
                             id = java.util.UUID.randomUUID().toString(),
                             orderIndex = index,
                             label = element.label,
                             anchor = element,
                             isOptional = isOptional
                         )
                    }
                    val tempPreset = com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset(
                        name = "Preview",
                        scope = com.autonion.automationcompanion.features.screen_understanding_ml.model.ScopeType.GLOBAL,
                        executionMode = com.autonion.automationcompanion.features.screen_understanding_ml.model.ExecutionMode.STRICT,
                        steps = steps
                    )
                    playPreset(tempPreset)
                } else {
                    android.widget.Toast.makeText(this, "Select elements to play", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onStop = {
                stopSelf() // Stop the service
            }
        )
        
        if (android.provider.Settings.canDrawOverlays(this)) {
            overlay?.show()
        } else {
             android.util.Log.e("ScreenUnderstanding", "Overlay permission missing, skipping overlay")
        }
        
        mediaProjectionCore?.startProjection(resultCode, data, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        


        scope.launch {
            mediaProjectionCore?.screenCaptureFlow?.collect { bitmap ->
                android.util.Log.d("ScreenUnderstanding", "Frame received: ${bitmap.width}x${bitmap.height}")
                val detections = perceptionLayer?.detect(bitmap) ?: emptyList()
                android.util.Log.d("ScreenUnderstanding", "Detections: ${detections.size}")
                val tracked = temporalTracker?.update(detections) ?: emptyList()
                
                latestElements = tracked // Update for playback loop
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                   overlay?.updateElements(tracked)
                }
            }
        }
    }

    // Removed duplicate onCreate and startCapture

    
    fun playPreset(preset: com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset) {
        if (isPlaying) {
             android.widget.Toast.makeText(this, "Already playing!", android.widget.Toast.LENGTH_SHORT).show()
             return
        }
        isPlaying = true
        android.widget.Toast.makeText(this, "Starting playback: ${preset.name}", android.widget.Toast.LENGTH_SHORT).show()
        
        scope.launch {
            try {
                for (step in preset.steps) {
                    if (!isPlaying) break
                    
                    android.util.Log.d("ScreenUnderstanding", "Executing step ${step.orderIndex}: ${step.label}")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        android.widget.Toast.makeText(this@ScreenUnderstandingService, "Looking for: ${step.label}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    
                    // 1. Wait for element to appear (Timeout: 5 seconds)
                    var foundElement: com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement? = null
                    val timeout = 5000L
                    val startTime = System.currentTimeMillis()
                    
                    while (System.currentTimeMillis() - startTime < timeout && isPlaying) {
                        // HACK: I will add a 'latestElements' variable to the Service and update it in the flow.
                        val currentElements = latestElements
                        
                        // Find matching element (label match)
                        val match = currentElements.find { it.label == step.label } // Simple label match for now
                        
                        if (match != null) {
                            foundElement = match
                            break
                        }
                        
                        kotlinx.coroutines.delay(200)
                    }
                    
                    if (!isPlaying) break
                    
                    if (foundElement != null) {
                        // 2. Click it
                        val centerX = (foundElement.bounds.left + foundElement.bounds.right) / 2
                        val centerY = (foundElement.bounds.top + foundElement.bounds.bottom) / 2
                        
                        val point = android.graphics.PointF(centerX, centerY)
                        val success = com.autonion.automationcompanion.features.screen_understanding_ml.logic.ActionExecutor.executeClick(point)
                        
                        if (success) {
                            android.util.Log.d("ScreenUnderstanding", "Clicked ${step.label}")
                        } else {
                             android.util.Log.e("ScreenUnderstanding", "Failed to click ${step.label} - Service not connected?")
                             withContext(kotlinx.coroutines.Dispatchers.Main) {
                                 android.widget.Toast.makeText(this@ScreenUnderstandingService, "Click failed. Is Accessibility Service enabled?", android.widget.Toast.LENGTH_LONG).show()
                             }
                             isPlaying = false
                             break
                        }
                        
                        // 3. Wait after click
                        kotlinx.coroutines.delay(2000) // Default action wait
                        
                    } else {
                        // Element not found
                        android.util.Log.w("ScreenUnderstanding", "Element ${step.label} not found within timeout")
                        if (preset.executionMode == com.autonion.automationcompanion.features.screen_understanding_ml.model.ExecutionMode.STRICT) {
                             withContext(kotlinx.coroutines.Dispatchers.Main) {
                                 android.widget.Toast.makeText(this@ScreenUnderstandingService, "Failed: ${step.label} not found", android.widget.Toast.LENGTH_LONG).show()
                             }
                            isPlaying = false
                            break
                        } else {
                            // Optional/Flexible: skip
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                 android.widget.Toast.makeText(this@ScreenUnderstandingService, "Skipping ${step.label}", android.widget.Toast.LENGTH_SHORT).show()
                             }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ScreenUnderstanding", "Playback error", e)
            } finally {
                isPlaying = false
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(this@ScreenUnderstandingService, "Playback finished", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun savePreset(name: String, elementsData: List<Pair<com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement, Boolean>>) {
        android.util.Log.d("ScreenUnderstanding", "savePreset called with ${elementsData.size} elements")
        if (elementsData.isEmpty()) {
            android.util.Log.w("ScreenUnderstanding", "Selection list is empty!")
            android.widget.Toast.makeText(this, "No elements selected to save", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        val steps = elementsData.mapIndexed { index, (element, isOptional) ->
             com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep(
                 id = java.util.UUID.randomUUID().toString(),
                 orderIndex = index,
                 label = element.label,
                 anchor = element,
                 isOptional = isOptional
             )
        }
        
        val preset = com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset(
            name = name,
            scope = com.autonion.automationcompanion.features.screen_understanding_ml.model.ScopeType.GLOBAL, // Default for now
            executionMode = com.autonion.automationcompanion.features.screen_understanding_ml.model.ExecutionMode.STRICT, // Default
            steps = steps
        )
        
        presetRepository?.savePreset(preset)
        android.widget.Toast.makeText(this, "Preset '$name' Saved with ${steps.size} steps!", android.widget.Toast.LENGTH_LONG).show()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Cancel scope first to stop new processing
        scope.cancel()
        
        mediaProjectionCore?.stopProjection()
        perceptionLayer?.close()
        overlay?.dismiss()
    }
}
