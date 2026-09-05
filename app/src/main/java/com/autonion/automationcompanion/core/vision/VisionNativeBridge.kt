package com.autonion.automationcompanion.core.vision

import android.graphics.Bitmap
import android.os.SystemClock
import java.nio.ByteBuffer

object VisionNativeBridge {

    init {
        System.loadLibrary("vision_engine")
    }

    @Volatile
    private var initialized = false
    private var generation = 0L

    external fun nativeInit(): String
    external fun nativeAddTemplate(
        id: Int,
        bitmap: Bitmap,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        threshold: Float,
        allowFullscreenFallback: Boolean,
        trackRoiToMatch: Boolean,
        moving: Boolean
    )
    external fun nativeClearTemplates()
    external fun nativeDestroy()
    external fun nativeMatch(bitmap: Bitmap, frameMs: Long): Array<MatchResultNative>
    private external fun nativeMatchRgba(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, frameMs: Long): Array<MatchResultNative>
    private external fun nativeResetMotion()
    external fun nativeRequestFullscreenSearch(id: Int)

    @Synchronized
    fun init(): String {
        generation++
        val result = nativeInit()
        initialized = true
        return result
    }

    @Synchronized
    private fun ensureInitialized() {
        if (!initialized) {
            nativeInit()
            initialized = true
        }
    }

    @Synchronized
    fun addTemplate(
        id: Int,
        bitmap: Bitmap,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        threshold: Float = 0.75f,
        allowFullscreenFallback: Boolean = true,
        trackRoiToMatch: Boolean = true,
        moving: Boolean = false
    ) {
        ensureInitialized()
        nativeAddTemplate(
            id,
            bitmap,
            roiX,
            roiY,
            roiW,
            roiH,
            threshold.coerceIn(0.5f, 1.0f),
            allowFullscreenFallback,
            trackRoiToMatch,
            moving
        )
    }

    @Synchronized
    fun clearTemplates() {
        generation++
        ensureInitialized()
        nativeClearTemplates()
    }

    @Synchronized
    fun requestFullscreenSearch(id: Int) {
        ensureInitialized()
        nativeRequestFullscreenSearch(id)
    }

    @Synchronized
    fun match(bitmap: Bitmap, frameMs: Long = SystemClock.uptimeMillis()): Array<MatchResultNative> {
        ensureInitialized()
        return nativeMatch(bitmap, frameMs)
    }

    @Synchronized
    fun matchRgba(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, frameMs: Long): Array<MatchResultNative> {
        ensureInitialized()
        return nativeMatchRgba(buffer.slice(), width, height, rowStride, frameMs)
    }

    @Synchronized
    fun resetMotion() {
        ensureInitialized()
        nativeResetMotion()
    }

    @Synchronized
    fun templateGeneration(): Long = generation

    @Synchronized
    fun release(expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != generation) return
        if (initialized) {
            nativeDestroy()
            initialized = false
        }
    }
}
