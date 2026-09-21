package com.autonion.automationcompanion.core.vision

import kotlin.math.abs
import kotlin.math.max

data class TapBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class PredictedTap(val x: Float, val y: Float)

fun predictMovingTap(
    match: MatchResultNative,
    observedAtMs: Long,
    nowMs: Long,
    bounds: TapBounds,
    leadMs: Int = 35
): PredictedTap? {
    val ageMs = nowMs - observedAtMs
    if (!match.matched || match.trackId <= 0 || match.observations < 2 ||
        ageMs !in 0..150 || match.width <= 0 || match.height <= 0 ||
        !match.velocityX.isFinite() || !match.velocityY.isFinite()) return null
    val horizon = (ageMs + leadMs.coerceIn(0, 150)) / 1000f
    val dx = match.velocityX * horizon
    val dy = match.velocityY * horizon
    // Do not extrapolate farther than one target width from the observation.
    if (max(abs(dx), abs(dy)) > max(match.width, match.height)) return null
    val x = match.x + match.width / 2f + dx
    val y = match.y + match.height / 2f + dy
    if (x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom) return null
    return PredictedTap(x, y)
}
