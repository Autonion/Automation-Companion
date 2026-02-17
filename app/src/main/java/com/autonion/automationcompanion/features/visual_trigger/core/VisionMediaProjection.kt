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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class VisionMediaProjection(
    private val context: Context,
    private val projectionManager: MediaProjectionManager
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private val _screenCaptureFlow = MutableSharedFlow<Bitmap>(
        replay = 1, 
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val screenCaptureFlow: SharedFlow<Bitmap> = _screenCaptureFlow.asSharedFlow()

    fun startProjection(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopProjection()
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
                        // bitmap.recycle() // Don't recycle if createBitmap returns same instance, but here it returns new
                        cropped
                    }

                    _screenCaptureFlow.tryEmit(finalBitmap)
                } catch (e: Exception) {
                    Log.e("VisionProjection", "Error converting image", e)
                } finally {
                    image.close()
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun stopProjection() {
        mediaProjection?.stop()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection = null
        virtualDisplay = null
        imageReader = null
    }
}
