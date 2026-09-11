package com.autonion.automationcompanion.features.screen_understanding_ml.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import com.autonion.automationcompanion.features.visual_trigger.core.VisionMediaProjection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

class MediaProjectionCore(
    context: Context,
    projectionManager: MediaProjectionManager,
    onProjectionLost: (() -> Unit)? = null
) {
    private val projection = VisionMediaProjection(context, projectionManager, onProjectionLost)
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

    // The backend queues frame signals, not old bitmaps waiting for slow inference.
    // Each collector owns its bitmap and must release it when finished.
    val screenCaptureFlow: Flow<Bitmap> = projection.screenCaptureFlow

    /** Drain idle frame signals without allocating screenshots or running detection. */
    fun captureFramesWhen(shouldProcess: () -> Boolean): Flow<Bitmap> =
        projection.frames.mapNotNull { frame -> if (shouldProcess()) frame.toBitmap() else null }

    /** Wake a newly enabled consumer even when the screen has not changed. */
    fun requestFreshFrame(): Int =
        projection.resizeCapture(captureWidth, captureHeight, captureDensity)

    fun startProjection(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        captureWidth = width
        captureHeight = height
        captureDensity = density
        projection.startProjection(resultCode, data, width, height, density)
    }

    /** A new output surface excludes queued frames and refreshes even an otherwise static screen. */
    suspend fun captureFreshBitmap(timeoutMs: Long = 1500): Bitmap? {
        var snapshot: Bitmap? = null
        var delivered = false
        try {
            // Reuse the existing VirtualDisplay and grant; do not create a second projection session.
            val generation = requestFreshFrame()
            val result = withTimeoutOrNull(timeoutMs) {
                projection.frames.mapNotNull { frame ->
                    if (frame.captureGeneration < generation) null
                    else frame.toBitmap().also { snapshot = it }
                }.firstOrNull()
            }
            delivered = result != null
            return result
        } finally {
            // A timeout/cancellation can race with bitmap conversion.
            if (!delivered) snapshot?.recycle()
        }
    }

    fun stopProjection() = projection.stopProjection()
}
