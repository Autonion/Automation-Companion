package com.autonion.automationcompanion.features.visual_trigger.models

import kotlinx.serialization.Serializable
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

@Serializable
data class VisionRegion(
    val id: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val templatePath: String,
    val action: VisionAction = VisionAction.Click,
    val color: Int,
    val sourceCapturePath: String? = null,  // Which screenshot this region was drawn on
    val matchThreshold: Float = 0.75f,      // Per-region match threshold (0.5–1.0)
    val rotationDegrees: Float = 0f,        // Rotation of the selected box around its center
    val searchX: Int? = null,               // Optional custom search ROI
    val searchY: Int? = null,
    val searchWidth: Int? = null,
    val searchHeight: Int? = null,
    val matchMode: VisionMatchMode = VisionMatchMode.STATIC,
    val movingMatchThreshold: Float = 0.80f,
    val tapLeadMs: Int = 35
) {
    val effectiveThreshold: Float
        get() = if (matchMode == VisionMatchMode.MOVING) movingMatchThreshold else matchThreshold
    // Helper to convert to Android Rect
    fun toRect(): Rect = Rect(x, y, x + width, y + height)

    fun customSearchRect(): Rect? {
        val sx = searchX ?: return null
        val sy = searchY ?: return null
        val sw = searchWidth ?: return null
        val sh = searchHeight ?: return null
        if (sw <= 0 || sh <= 0) return null
        return Rect(sx, sy, sx + sw, sy + sh)
    }

    fun toSearchRect(): Rect {
        return customSearchRect() ?: rotatedBounds(toRect(), rotationDegrees)
    }
    
    companion object {
        fun fromRect(
            id: Int,
            rect: Rect,
            templatePath: String,
            action: VisionAction,
            color: Int,
            sourceCapturePath: String? = null,
            matchThreshold: Float = 0.75f,
            rotationDegrees: Float = 0f,
            searchRect: Rect? = null,
            matchMode: VisionMatchMode = VisionMatchMode.STATIC,
            movingMatchThreshold: Float = 0.80f,
            tapLeadMs: Int = 35
        ): VisionRegion {
            return VisionRegion(
                id = id,
                x = rect.left,
                y = rect.top,
                width = rect.width(),
                height = rect.height(),
                templatePath = templatePath,
                action = action,
                color = color,
                sourceCapturePath = sourceCapturePath,
                matchThreshold = matchThreshold,
                rotationDegrees = rotationDegrees,
                searchX = searchRect?.left,
                searchY = searchRect?.top,
                searchWidth = searchRect?.width(),
                searchHeight = searchRect?.height(),
                matchMode = matchMode,
                movingMatchThreshold = movingMatchThreshold,
                tapLeadMs = tapLeadMs
            )
        }

        private fun rotatedBounds(rect: Rect, rotationDegrees: Float): Rect {
            if (abs(rotationDegrees) < 0.01f) return Rect(rect)

            val radians = Math.toRadians(rotationDegrees.toDouble())
            val cos = cos(radians)
            val sin = sin(radians)
            val cx = rect.left + rect.width() / 2.0
            val cy = rect.top + rect.height() / 2.0
            val points = listOf(
                rect.left.toDouble() to rect.top.toDouble(),
                rect.right.toDouble() to rect.top.toDouble(),
                rect.right.toDouble() to rect.bottom.toDouble(),
                rect.left.toDouble() to rect.bottom.toDouble()
            ).map { (x, y) ->
                val dx = x - cx
                val dy = y - cy
                (cx + dx * cos - dy * sin) to (cy + dx * sin + dy * cos)
            }

            return Rect(
                floor(points.minOf { it.first }).toInt(),
                floor(points.minOf { it.second }).toInt(),
                ceil(points.maxOf { it.first }).toInt(),
                ceil(points.maxOf { it.second }).toInt()
            )
        }
    }
}

@Serializable
enum class VisionMatchMode { STATIC, MOVING }
