package com.autonion.automationcompanion.features.visual_trigger.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import com.autonion.automationcompanion.core.vision.visionFrameTime

class VisionFrame internal constructor(
    val image: Image,
    val acquiredAtMs: Long,
    private val release: () -> Unit
) : AutoCloseable {
    val width = image.width
    val height = image.height
    private val timing = visionFrameTime(image.timestamp, System.nanoTime(), acquiredAtMs)
    val observedAtMs = timing.observedAtMs
    val timestampSource = if (timing.producerTimestampUsed) "producer-monotonic" else "acquisition-fallback"
    private val closed = AtomicBoolean(false)

    fun toBitmap(): Bitmap {
        check(!closed.get())
        val plane = image.planes[0]
        require(plane.pixelStride == 4) { "Unsupported capture pixel stride" }
        val paddedWidth = plane.rowStride / plane.pixelStride
        val source = plane.buffer.duplicate()
        // Some producers omit padding on the last row of the buffer.
        val needsPacking = source.remaining() < plane.rowStride * height
        val pixels = if (needsPacking) {
            ByteBuffer.allocateDirect(width * height * 4).apply {
                repeat(height) { row ->
                    source.limit(row * plane.rowStride + width * 4)
                    source.position(row * plane.rowStride)
                    put(source)
                }
                rewind()
            }
        } else source
        val bitmapWidth = if (needsPacking) width else paddedWidth
        val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.copyPixelsFromBuffer(pixels)
            if (bitmapWidth == width) return bitmap
            return Bitmap.createBitmap(bitmap, 0, 0, width, height).also { bitmap.recycle() }
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

class VisionMediaProjection(
    private val context: Context,
    private val projectionManager: MediaProjectionManager,
    private val onProjectionLost: (() -> Unit)? = null
) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val captureLock = Any()
    private var activeFrames = 0
    private var released = false
    private val captureThread = HandlerThread("VisionCapture").apply { start() }
    private val available = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var stoppedByUser = false

    // Queue only signals. Acquire the newest image when processing is ready,
    // with no full-screen bitmap allocations on the main thread.
    val frames: Flow<VisionFrame> = flow {
        for (signal in available) {
            val frame = synchronized(captureLock) {
                if (released) null else imageReader?.acquireLatestImage()?.let { image ->
                    activeFrames++
                    VisionFrame(image, SystemClock.uptimeMillis()) {
                        synchronized(captureLock) {
                            image.close()
                            activeFrames--
                            if (released && activeFrames == 0) closeReader()
                        }
                    }
                }
            } ?: continue
            try {
                emit(frame)
            } finally {
                frame.close()
            }
        }
    }

    // Existing one-shot flow consumers own the returned bitmap.
    val screenCaptureFlow: Flow<Bitmap> = frames.map { it.toBitmap() }

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
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener({ available.trySend(Unit) }, Handler(captureThread.looper))
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "VisionTriggerDisplay", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun closeReader() {
        imageReader?.close()
        imageReader = null
    }

    private fun releaseResources() {
        synchronized(captureLock) {
            if (released) return
            released = true
            available.close()
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            virtualDisplay = null
            // Do not invalidate an Image that a synchronous JNI call is reading.
            if (activeFrames == 0) closeReader()
            captureThread.quitSafely()
        }
    }

    fun stopProjection() {
        stoppedByUser = true
        mediaProjection?.stop()
        releaseResources()
        mediaProjection = null
    }
}
