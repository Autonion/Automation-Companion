package com.autonion.automationcompanion.features.gesture_recording_playback.overlay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import android.view.accessibility.AccessibilityEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.gesture_recording_playback.managers.ActionManager
import com.autonion.automationcompanion.features.gesture_recording_playback.models.Action
import com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType
import com.autonion.automationcompanion.features.system_context_automation.app_specific.engine.AppSpecificAutomationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AutomationService : AccessibilityService() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isPlaying = false
    private var loopCount = 1
    private var currentLoop = 0
    private var infiniteLoop = false

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                OverlayService.ACTION_PLAY -> startPlayback()
                OverlayService.ACTION_STOP -> stopPlayback()
                OverlayService.ACTION_LOOP_COUNT_CHANGED -> {
                    val count = intent.getIntExtra(OverlayService.EXTRA_LOOP_COUNT, 1)
                    setLoopCount(count)
                }
            }
        }
    }

    private var appSpecificEngine: AppSpecificAutomationEngine? = null

    private var defaultFlags: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("AutomationService", "Service connected")
        
        // Initialize ExclusionManager
        com.autonion.automationcompanion.core.settings.ExclusionManager.initialize(this)
        
        // Store default flags
        serviceInfo?.let {
            defaultFlags = it.flags
            Log.d("AutomationService", "Default flags stored: $defaultFlags")
        }
        
        if (appSpecificEngine == null) {
            appSpecificEngine = AppSpecificAutomationEngine(this)
            AccessibilityRouter.register(appSpecificEngine!!)
        }
        
        AccessibilityRouter.onServiceConnected(this)
        setupBroadcastReceiver()

        // Start Cross-Device Automation Manager (Background Persistence)
        com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager.getInstance(this).start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Intercept Window State Changed to check for excluded apps
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { packageName ->
                checkExclusion(packageName)
            }
        }
        AccessibilityRouter.onEvent(this, event)
    }

    private fun checkExclusion(packageName: String) {
        val isExcluded = com.autonion.automationcompanion.core.settings.ExclusionManager.isExcluded(packageName)
        val isStrictMode = com.autonion.automationcompanion.core.settings.ExclusionManager.isStrictMode.value
        val info = serviceInfo ?: return
        
        if (isExcluded) {
            if (isStrictMode) {
                Log.w("AutomationService", "Strict Mode: Disabling service due to excluded app $packageName")
                // Notify user
                showDisabledNotification(packageName)
                // Disable service
                disableSelf()
                return
            }

            // Remove flags that might trigger detection
            val newFlags = info.flags and 
                    (android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()) and
                    (android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv())
            
            if (info.flags != newFlags) {
                Log.i("AutomationService", "Entering restricted mode for excluded app: $packageName")
                info.flags = newFlags
                serviceInfo = info
            }
        } else {
            // Restore default flags if needed
            if (info.flags != defaultFlags) {
                Log.i("AutomationService", "Restoring full access from $packageName")
                info.flags = defaultFlags
                serviceInfo = info
            }
        }
    }

    private fun showDisabledNotification(triggerPackage: String) {
        // We warn the user that the service killed itself
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "automation_status_channel"
        
        // Ensure channel exists (re-using checking code usually in App)
        val channel = android.app.NotificationChannel(
            channelId,
            "Automation Status",
            android.app.NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = android.app.Notification.Builder(this, channelId)
            .setContentTitle("Automation Service Disabled")
            .setContentText("Disabled for security ($triggerPackage). Tap to re-enable.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(999, notification)
    }

    private fun setupBroadcastReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(OverlayService.ACTION_PLAY)
            addAction(OverlayService.ACTION_STOP)
            addAction(OverlayService.ACTION_LOOP_COUNT_CHANGED)
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
            playbackReceiver,
            intentFilter
        )
    }

    private fun startPlayback() {
        if (isPlaying) return
        Log.d("AutomationService", "Starting playback")
        DebugLogger.info(
            this, LogCategory.GESTURE_RECORDING,
            "Playback started",
            "Starting gesture playback (loops=${if (infiniteLoop) "∞" else loopCount.toString()})",
            "AutomationService"
        )

        isPlaying = true
        currentLoop = 0
        scope.launch {
            // Add initial delay to allow overlay window to settle after mode switch
            delay(500)

            while (isPlaying && (infiniteLoop || currentLoop < loopCount)) {
                Log.d("AutomationService", "Loop $currentLoop started")
                executeActions()
                currentLoop++
                if (!infiniteLoop && currentLoop >= loopCount) {
                    stopPlayback()
                    // Send Intent to OverlayService to update UI
                    val intent = Intent(OverlayService.ACTION_STOP)
                    LocalBroadcastManager.getInstance(this@AutomationService).sendBroadcast(intent)
                    break
                }
                delay(100) // Small delay between loops
            }
        }
        broadcastPlaybackState(true)
    }

    private fun stopPlayback() {
        isPlaying = false
        Log.d("AutomationService", "Playback stopped")
        DebugLogger.info(
            this, LogCategory.GESTURE_RECORDING,
            "Playback stopped",
            "Completed $currentLoop loop(s)",
            "AutomationService"
        )
        scope.coroutineContext.cancelChildren()
        broadcastPlaybackState(false)
    }

    private suspend fun executeActions() {
        // Ensure we are getting a list of actions
        val actions = ActionManager.getActions()
        Log.d("AutomationService", "Executing ${actions.size} actions")
        DebugLogger.info(
            this@AutomationService, LogCategory.GESTURE_RECORDING,
            "Executing ${actions.size} actions",
            "Loop ${currentLoop + 1}: running ${actions.count { it.isEnabled }} enabled actions",
            "AutomationService"
        )

        // Iterate over the list manually or use standard loop to avoid ambiguity if any
        for (i in actions.indices) {
            val action = actions[i]
            if (!isPlaying) break
            if (!action.isEnabled) continue

            delay(action.delayBefore)
            Log.d("AutomationService", "Executing action: ${action.type}")
            executeAction(action)

            // Enforce minimum delay if not set (handles legacy actions)
            val waitTime = if (action.delayAfter < 100) 500 else action.delayAfter
            delay(waitTime)
        }
    }

    private suspend fun executeAction(action: Action) {
        when (action.type) {
            ActionType.CLICK -> {
                if (action.points.isNotEmpty()) {
                    performClick(action.points[0], action.duration)
                }
            }
            ActionType.SWIPE -> {
                if (action.points.size >= 2) {
                    performSwipe(action.points[0], action.points[1], action.duration)
                }
            }
            ActionType.LONG_CLICK -> {
                if (action.points.isNotEmpty()) {
                    performLongClick(action.points[0], action.duration)
                }
            }
            ActionType.WAIT -> {
                delay(action.duration)
            }
        }
    }

    private suspend fun performClick(point: PointF, duration: Long) {
        Log.d("AutomationService", "Performing Click at ${point.x}, ${point.y} with duration $duration")
        val clickPath = Path().apply {
            moveTo(point.x, point.y)
            lineTo(point.x + 1, point.y + 1)
        }

        // Use at least 50ms or the action duration if larger
        val strokeDuration = if (duration < 50) 50 else duration

        val gestureBuilder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, strokeDuration))

        dispatchGestureSuspending(gestureBuilder.build())
    }

    private suspend fun performSwipe(start: PointF, end: PointF, duration: Long) {
        Log.d("AutomationService", "Performing Swipe from ${start.x},${start.y} to ${end.x},${end.y} with duration $duration")
        val swipePath = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }

        val gestureBuilder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(swipePath, 0, duration))

        dispatchGestureSuspending(gestureBuilder.build())
    }

    private suspend fun performLongClick(point: PointF, duration: Long) {
        Log.d("AutomationService", "Performing Long Click at ${point.x}, ${point.y} with duration $duration")
        val clickPath = Path().apply {
            moveTo(point.x, point.y)
            lineTo(point.x + 1, point.y + 1)
        }

        // Use the action duration for long click (minimum 100ms)
        val strokeDuration = if (duration < 100) 500 else duration

        val gestureBuilder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(clickPath, 0, strokeDuration))

        dispatchGestureSuspending(gestureBuilder.build())
    }

    private suspend fun dispatchGestureSuspending(gesture: GestureDescription): Boolean {
        return suspendCoroutine { continuation ->
            try {
                val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d("AutomationService", "Gesture completed")
                        continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.d("AutomationService", "Gesture cancelled")
                        continuation.resume(false)
                    }
                }, null)

                if (!dispatched) {
                    Log.e("AutomationService", "Gesture dispatch failed immediately")
                    DebugLogger.error(
                        this@AutomationService, LogCategory.GESTURE_RECORDING,
                        "Gesture dispatch failed",
                        "Gesture could not be dispatched to accessibility service",
                        "AutomationService"
                    )
                    continuation.resume(false)
                }
            } catch (e: Exception) {
                Log.e("AutomationService", "Error dispatching gesture", e)
                DebugLogger.error(
                    this@AutomationService, LogCategory.GESTURE_RECORDING,
                    "Gesture error",
                    "Exception: ${e.message}",
                    "AutomationService"
                )
                continuation.resume(false)
            }
        }
    }

    private fun broadcastPlaybackState(isPlaying: Boolean) {
        val intent = Intent(
            if (isPlaying) "PLAYBACK_STARTED" else "PLAYBACK_STOPPED"
        )
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onInterrupt() {
        stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        scope.cancel()
        AccessibilityRouter.onServiceDestroyed()
        
        // Stop Cross-Device Automation Manager
        com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager.getInstance(this).stop()
    }

    fun setLoopCount(count: Int) {
        loopCount = count
        infiniteLoop = count <= 0
    }
}