package com.autonion.automationcompanion.features.screen_understanding_ml.core

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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MediaProjectionCore(
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
    
    // Emissions of screen bitmaps
    private val _screenCaptureFlow = MutableSharedFlow<Bitmap>(replay = 1)
    val screenCaptureFlow: SharedFlow<Bitmap> = _screenCaptureFlow.asSharedFlow()

    fun startProjection(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        stoppedByUser = false
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        // Callback to handle stop — fires when OS revokes projection (screen off, etc.)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                releaseResources()
                if (!stoppedByUser) {
                    android.util.Log.w("MediaProjectionCore", "Projection lost externally")
                    onProjectionLost?.invoke()
                }
            }
        }, Handler(Looper.getMainLooper()))

        setupVirtualDisplay(width, height, density)
    }

    private fun setupVirtualDisplay(width: Int, height: Int, density: Int) {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenUnderstandingDisplay",
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
    
                    // Create bitmap
                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    
                    // Emit bitmap
                    // Note: We should probably crop the padding if necessary, but for ML it might be fine or resized anyway.
                    // For precise UI work, we might want to crop.
                    // Let's crop to exact width if padding exists
                    val finalBitmap = if (rowPadding == 0) {
                        bitmap
                    } else {
                        val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        bitmap.recycle() // Recycle original
                        cropped
                    }

                    _screenCaptureFlow.tryEmit(finalBitmap)
                } catch (e: Exception) {
                    android.util.Log.e("MediaProjectionCore", "Error converting image to bitmap", e)
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
