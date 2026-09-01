package com.autonion.automationcompanion.features.visual_trigger.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class VisionMediaProjection(
    private val context: Context,
    private val projectionManager: MediaProjectionManager,
    private val onProjectionLost: (() -> Unit)? = null
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /** True when [stopProjection] is called by the consumer, to avoid firing [onProjectionLost]. */
    @Volatile
    private var stoppedByUser = false
    
    private val screenCaptureChannel = Channel<Bitmap>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    )
    val screenCaptureFlow: Flow<Bitmap> = screenCaptureChannel.receiveAsFlow()

    fun startProjection(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        stoppedByUser = false
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseResources()
                if (!stoppedByUser) {
                    Log.w("VisionProjection", "Projection lost externally")
                    onProjectionLost?.invoke()
                }
            }
        }, Handler(Looper.getMainLooper()))

        setupVirtualDisplay(width, height, density)
    }

    private fun setupVirtualDisplay(width: Int, height: Int, density: Int) {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "VisionTriggerDisplay",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                try {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width
    
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    
                    val finalBitmap = if (rowPadding == 0) {
                        bitmap
                    } else {
                        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        bitmap.recycle() // Safe: createBitmap returned a new instance when cropping
                        cropped
                    }

                    if (screenCaptureChannel.trySend(finalBitmap).isFailure && !finalBitmap.isRecycled) {
                        finalBitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e("VisionProjection", "Error converting image", e)
                } finally {
                    image.close()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    /** Release internal resources without calling [MediaProjection.stop]. */
    private fun releaseResources() {
        virtualDisplay?.release()
        imageReader?.close()
        screenCaptureChannel.close()
        virtualDisplay = null
        imageReader = null
    }

    fun stopProjection() {
        stoppedByUser = true
        mediaProjection?.stop()
        releaseResources()
        mediaProjection = null
    }
}
