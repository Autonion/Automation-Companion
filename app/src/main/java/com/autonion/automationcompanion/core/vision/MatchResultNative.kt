package com.autonion.automationcompanion.core.vision

data class MatchResultNative(
    val id: Int = 0,
    val matched: Boolean = false,
    val score: Float = 0f,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val trackId: Int = 0,
    val velocityX: Float = 0f,
    val velocityY: Float = 0f,
    val observations: Int = 0
)
