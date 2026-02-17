package com.autonion.automationcompanion.core.vision

data class MatchResultNative(
    val id: Int = 0,
    val matched: Boolean = false,
    val score: Float = 0f,
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0
)
