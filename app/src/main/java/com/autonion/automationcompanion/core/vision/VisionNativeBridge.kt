package com.autonion.automationcompanion.core.vision

import android.graphics.Bitmap

object VisionNativeBridge {

    init {
        System.loadLibrary("vision_engine")
    }

    @Volatile
    private var initialized = false

    external fun nativeInit(): String
    external fun nativeAddTemplate(id: Int, bitmap: Bitmap, roiX: Int, roiY: Int, roiW: Int, roiH: Int, threshold: Float)
    external fun nativeClearTemplates()
    external fun nativeDestroy()
    external fun nativeMatch(bitmap: Bitmap): Array<MatchResultNative>
    external fun nativeRequestFullscreenSearch(id: Int)

    @Synchronized
    fun init(): String {
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
    fun addTemplate(id: Int, bitmap: Bitmap, roiX: Int, roiY: Int, roiW: Int, roiH: Int, threshold: Float = 0.75f) {
        ensureInitialized()
        nativeAddTemplate(id, bitmap, roiX, roiY, roiW, roiH, threshold.coerceIn(0.5f, 1.0f))
    }

    @Synchronized
    fun clearTemplates() {
        ensureInitialized()
        nativeClearTemplates()
    }

    @Synchronized
    fun requestFullscreenSearch(id: Int) {
        ensureInitialized()
        nativeRequestFullscreenSearch(id)
    }

    @Synchronized
    fun match(bitmap: Bitmap): Array<MatchResultNative> {
        ensureInitialized()
        return nativeMatch(bitmap)
    }

    @Synchronized
    fun release() {
        if (initialized) {
            nativeDestroy()
            initialized = false
        }
    }
}
