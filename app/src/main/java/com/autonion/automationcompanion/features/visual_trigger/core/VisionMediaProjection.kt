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
    val captureGeneration: Int,
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
    private class ReaderSlot(val reader: ImageReader, val generation: Int) {
        var activeFrames = 0
        var retired = false
    }
    private var readerSlot: ReaderSlot? = null
    private val captureLock = Any()
    private var nextGeneration = 0
    private var released = false
    private val captureThread = HandlerThread("VisionCapture").apply { start() }
    private val available = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var stoppedByUser = false
    var onCapturedContentResize: ((Int, Int) -> Unit)? = null

    // Queue only signals. Acquire the newest image when processing is ready,
    // with no full-screen bitmap allocations on the main thread.
    val frames: Flow<VisionFrame> = flow {
        for (signal in available) {
            val frame = synchronized(captureLock) {
                val slot = readerSlot
                if (released || slot == null) null else slot.reader.acquireLatestImage()?.let { image ->
                    slot.activeFrames++
                    VisionFrame(image, SystemClock.uptimeMillis(), slot.generation) {
                        synchronized(captureLock) {
                            image.close()
                            slot.activeFrames--
                            if (slot.retired && slot.activeFrames == 0) slot.reader.close()
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
            override fun onCapturedContentResize(width: Int, height: Int) {
                onCapturedContentResize?.invoke(width, height)
            }
            override fun onStop() {
                releaseResources()
                if (!stoppedByUser) {
                    Log.w("VisionProjection", "Projection lost externally")
                    onProjectionLost?.invoke()
                }
            }
        }, Handler(Looper.getMainLooper()))
        readerSlot = createReader(width, height)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "VisionTriggerDisplay", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            readerSlot?.reader?.surface, null, null
        )
    }

    private fun createReader(width: Int, height: Int): ReaderSlot {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ available.trySend(Unit) }, Handler(captureThread.looper))
        return ReaderSlot(reader, ++nextGeneration)
    }

    private fun retire(slot: ReaderSlot?) {
        slot ?: return
        slot.retired = true
        slot.reader.setOnImageAvailableListener(null, null)
        if (slot.activeFrames == 0) slot.reader.close()
    }

    fun captureGeneration(): Int = synchronized(captureLock) { readerSlot?.generation ?: -1 }

    fun resizeCapture(width: Int, height: Int, density: Int): Int = synchronized(captureLock) {
        check(!released) { "Capture already stopped" }
        val display = checkNotNull(virtualDisplay) { "Projection not started" }
        val replacement = createReader(width, height)
        try {
            display.surface = null
            display.resize(width, height, density)
            display.surface = replacement.reader.surface
        } catch (error: Exception) {
            retire(replacement)
            throw error
        }
        val previous = readerSlot
        readerSlot = replacement
        while (available.tryReceive().isSuccess) Unit
        retire(previous)
        Log.i("VisionProjection", "Capture resized: ${width}x$height generation=${replacement.generation}")
        available.trySend(Unit)
        replacement.generation
    }

    private fun releaseResources() {
        synchronized(captureLock) {
            if (released) return
            released = true
            available.close()
            virtualDisplay?.release()
            virtualDisplay = null
            retire(readerSlot)
            readerSlot = null
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
