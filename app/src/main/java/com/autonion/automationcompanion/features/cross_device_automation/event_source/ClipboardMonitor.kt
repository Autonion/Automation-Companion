package com.autonion.automationcompanion.features.cross_device_automation.event_source

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.autonion.automationcompanion.AccessibilityFeature
import com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class ClipboardMonitor(
    private val context: Context,
    private val eventReceiver: EventReceiver
) : AccessibilityFeature {

    private var activeService: AccessibilityService? = null
    private var clipboardManager: ClipboardManager? = null
    private val TAG = "ClipboardMonitor"
    private var isMonitoring = false
    private var lastClipboardContent: String? = null

    override fun onServiceConnected(service: AccessibilityService) {
        Log.d(TAG, "Accessibility Service Connected: $service")
        activeService = service
        clipboardManager = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        start()
    }
    
    override fun onServiceDisconnected() {
        Log.d(TAG, "Accessibility Service Disconnected")
        stop()
        activeService = null
        clipboardManager = null
    }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isMonitoring) return@OnPrimaryClipChangedListener
        readAndBroadcastClipboard()
    }

    fun checkNow(activityContext: Context? = null) {
        // If activityContext is provided, use it (Foreground check)
        // Otherwise try activeService (Background/Service check)
        readAndBroadcastClipboard(activityContext)
    }

    private fun readAndBroadcastClipboard(contextOverride: Context? = null) {
        val manager: ClipboardManager? = if (contextOverride != null) {
            contextOverride.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } else {
            activeService?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        }

        if (manager == null) {
            // Log.w(TAG, "No ClipboardManager available (Service=${activeService != null}, Context=${contextOverride != null})")
            return 
        }

        try {
            val clip = manager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                
                // Logic to detect change. 
                // Note: If we just restarted the app, lastClipboardContent might be null, so we send the event.
                // This is desired for "Sync on Return".
                if (!text.isNullOrEmpty() && text != lastClipboardContent) {
                    Log.d(TAG, "Clipboard content changed/detected: '$text'")
                    lastClipboardContent = text
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        val event = RawEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            type = "clipboard.text_copied",
                            payload = mapOf("text" to text),
                            sourceDeviceId = "local"
                        )
                        eventReceiver.onEventReceived(event)
                    }
                }
            }
        } catch (e: Exception) {
            // This is expected to fail in background on Android 10+
            // We ignore it here because we will re-check on 'onResume' via checkNow()
            Log.v(TAG, "Failed to read clipboard (likely background restriction): ${e.message}")
        }
    }

    fun start() {
        if (isMonitoring || clipboardManager == null) return
        isMonitoring = true
        Log.d(TAG, "Clipboard monitoring started (Listener Mode)")
        try {
            clipboardManager?.addPrimaryClipChangedListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add clipboard listener", e)
        }
    }

    fun stop() {
        if (!isMonitoring) return
        isMonitoring = false
        try {
            clipboardManager?.removePrimaryClipChangedListener(listener)
        } catch (e: Exception) {
             Log.e(TAG, "Failed to remove clipboard listener", e)
        }
        Log.d(TAG, "Clipboard monitoring stopped")
    }


}
