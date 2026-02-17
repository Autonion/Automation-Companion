package com.autonion.automationcompanion.features.visual_trigger.models

import kotlinx.serialization.Serializable
import android.graphics.Rect

@Serializable
data class VisionRegion(
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val templatePath: String,
    val action: VisionAction = VisionAction.Click,
    val color: Int
) {
    // Helper to convert to Android Rect
    fun toRect(): Rect = Rect(x, y, x + width, y + height)
    
    companion object {
        fun fromRect(id: Int, rect: Rect, templatePath: String, action: VisionAction, color: Int): VisionRegion {
            return VisionRegion(
                id = id,
                x = rect.left,
                y = rect.top,
                width = rect.width(),
                height = rect.height(),
                templatePath = templatePath,
                action = action,
                color = color
            )
        }
    }
}
