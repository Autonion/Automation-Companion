package com.autonion.automationcompanion.features.flow_automation.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.util.Log
import com.autonion.automationcompanion.features.visual_trigger.core.VisionMediaProjection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "ScreenCaptureProvider"

/**
 * Shared screen capture provider that wraps [VisionMediaProjection].
 *
 * Used by VisualTriggerNodeExecutor and ScreenMLNodeExecutor to get
 * live screen frames without each executor managing its own MediaProjection.
 */
class ScreenCaptureProvider(private val context: Context) {

    private var projection: VisionMediaProjection? = null
    @Volatile
    private var latestBitmap: Bitmap? = null
    private var isStarted = false

    /**
     * Initialize and start the MediaProjection screen capture.
     * Must be called from a foreground service context.
     */
    fun start(resultCode: Int, resultData: Intent) {
        if (isStarted) {
            Log.d(TAG, "Already started, skipping")
            return
        }

        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val metrics = context.resources.displayMetrics

        projection = VisionMediaProjection(context, mpManager).also { vmp ->
            vmp.startProjection(
                resultCode, resultData,
                metrics.widthPixels, metrics.heightPixels, metrics.densityDpi
            )
        }
        isStarted = true
        Log.d(TAG, "Screen capture started: ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    /**
     * Capture the latest screen frame. Waits up to [timeoutMs] for a frame.
     * Returns null if no frame arrives in time.
     */
    suspend fun captureFrame(timeoutMs: Long = 3000L): Bitmap? {
        val vmp = projection ?: run {
            Log.e(TAG, "captureFrame called but projection is not started")
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                vmp.screenCaptureFlow.first()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing frame", e)
            null
        }
    }

    /**
     * Stop the screen capture and release resources.
     */
    fun stop() {
        projection?.stopProjection()
        projection = null
        latestBitmap?.recycle()
        latestBitmap = null
        isStarted = false
        Log.d(TAG, "Screen capture stopped")
    }

    fun isActive(): Boolean = isStarted
}
