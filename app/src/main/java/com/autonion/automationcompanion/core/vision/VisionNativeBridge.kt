package com.autonion.automationcompanion.core.vision

import android.graphics.Bitmap

object VisionNativeBridge {

    init {
        System.loadLibrary("vision_engine")
    }

    external fun nativeInit(): String
    external fun nativeAddTemplate(id: Int, bitmap: Bitmap)
    external fun nativeClearTemplates()
    external fun nativeMatch(bitmap: Bitmap): Array<MatchResultNative>

    fun init() = nativeInit()
    fun addTemplate(id: Int, bitmap: Bitmap) = nativeAddTemplate(id, bitmap)
    fun clearTemplates() = nativeClearTemplates()
    fun match(bitmap: Bitmap): Array<MatchResultNative> = nativeMatch(bitmap)
    fun release() = nativeClearTemplates()
}
