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
        startForegroundService()
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

    
    // Coroutine scope
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_CAPTURE") {
            val resultCode = intent.getIntExtra("resultCode", 0)
            val data = intent.getParcelableExtra<Intent>("data")
            val presetName = intent.getStringExtra("presetName")
            
            if (resultCode != 0 && data != null) {
                startCapture(resultCode, data, presetName)
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
                
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                   overlay?.updateElements(tracked)
                }
            }
        }
    }

    // Removed duplicate onCreate and startCapture

    
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
