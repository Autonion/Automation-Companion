package com.autonion.automationcompanion.features.cross_device_automation.event_source

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
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
    private var lastImageHash: Int? = null

    // Maximum image size for sync: 5MB base64 (approx 3.75MB raw)
    private val MAX_IMAGE_BYTES = 5 * 1024 * 1024

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
        val effectiveContext = contextOverride ?: activeService as? Context
        val manager: ClipboardManager? = if (contextOverride != null) {
            contextOverride.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        } else {
            activeService?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        }

        if (manager == null) {
            return 
        }

        try {
            val clip = manager.primaryClip
            if (clip == null || clip.itemCount == 0) return

            val item = clip.getItemAt(0)
            val description = clip.description

            // Check for image content first
            if (effectiveContext != null && description != null) {
                for (i in 0 until description.mimeTypeCount) {
                    val mimeType = description.getMimeType(i)
                    if (mimeType.startsWith("image/")) {
                        handleImageClipboard(item, mimeType, effectiveContext)
                        return
                    }
                }
            }

            // Text content
            val text = item.text?.toString()
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
        } catch (e: Exception) {
            // This is expected to fail in background on Android 10+
            // We ignore it here because we will re-check on 'onResume' via checkNow()
            Log.v(TAG, "Failed to read clipboard (likely background restriction): ${e.message}")
        }
    }

    private fun handleImageClipboard(
        item: android.content.ClipData.Item,
        mimeType: String,
        effectiveContext: Context
    ) {
        val uri = item.uri ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputStream = effectiveContext.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    Log.w(TAG, "Could not open input stream for image URI: $uri")
                    return@launch
                }

                val bytes = inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) return@launch

                // Deduplicate by hash
                val hash = bytes.contentHashCode()
                if (hash == lastImageHash) return@launch
                lastImageHash = hash

                // Size check
                if (bytes.size > MAX_IMAGE_BYTES) {
                    Log.w(TAG, "Image too large for clipboard sync: ${bytes.size / 1024}KB (max ${MAX_IMAGE_BYTES / 1024}KB)")
                    return@launch
                }

                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Log.d(TAG, "Image clipboard detected: $mimeType, ${bytes.size / 1024}KB")

                val event = RawEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    type = "clipboard.image_copied",
                    payload = mapOf(
                        "image_base64" to base64,
                        "mime_type" to mimeType,
                        "size_bytes" to bytes.size.toString()
                    ),
                    sourceDeviceId = "local"
                )
                eventReceiver.onEventReceived(event)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read image from clipboard: ${e.message}")
            }
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

