package com.autonion.automationcompanion.core.vision

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class VisionCaptureGeometry(
    val screenWidth: Int,
    val screenHeight: Int,
    val captureWidth: Int,
    val captureHeight: Int
) {
    val scaleX: Double get() = captureWidth.toDouble() / screenWidth
    val scaleY: Double get() = captureHeight.toDouble() / screenHeight

    fun captureX(x: Int): Int = floor(x * scaleX).toInt().coerceIn(0, captureWidth)
    fun captureY(y: Int): Int = floor(y * scaleY).toInt().coerceIn(0, captureHeight)
    fun captureRight(x: Int): Int = ceil(x * scaleX).toInt().coerceIn(0, captureWidth)
    fun captureBottom(y: Int): Int = ceil(y * scaleY).toInt().coerceIn(0, captureHeight)
    fun templateWidth(width: Int): Int = (width * scaleX).roundToInt().coerceAtLeast(1)
    fun templateHeight(height: Int): Int = (height * scaleY).roundToInt().coerceAtLeast(1)

    fun toScreen(hit: MatchResultNative): MatchResultNative = hit.copy(
        x = (hit.x / scaleX).roundToInt(), y = (hit.y / scaleY).roundToInt(),
        width = (hit.width / scaleX).roundToInt(), height = (hit.height / scaleY).roundToInt(),
        velocityX = (hit.velocityX / scaleX).toFloat(), velocityY = (hit.velocityY / scaleY).toFloat()
    )

    companion object {
        fun create(width: Int, height: Int, smallestTargetSide: Int, allowScaling: Boolean): VisionCaptureGeometry {
            require(width > 0 && height > 0)
            val scale = if (allowScaling) {
                maxOf(720.0 / maxOf(width, height), 20.0 / smallestTargetSide.coerceAtLeast(1)).coerceAtMost(1.0)
            } else 1.0
            return VisionCaptureGeometry(width, height,
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1))
        }
    }
}

data class VisionFrameTime(val observedAtMs: Long, val producerTimestampUsed: Boolean)

// Image timestamps have producer-specific timebases. Use only plausible
// monotonic timestamps; otherwise report acquisition age explicitly.
fun visionFrameTime(timestampNs: Long, nowNs: Long, acquiredAtMs: Long): VisionFrameTime {
    val ageNs = nowNs - timestampNs
    return if (timestampNs > 0 && ageNs in 0..5_000_000_000L) {
        VisionFrameTime(acquiredAtMs - ageNs / 1_000_000, true)
    } else VisionFrameTime(acquiredAtMs, false)
}

fun isFreshVisionFrame(observedAtMs: Long, nowMs: Long): Boolean = nowMs - observedAtMs in 0..150
