package com.autonion.automationcompanion.features.screen_understanding_ml.model

import kotlinx.serialization.Serializable

/**
 * A pre-captured text node from the accessibility tree.
 * Captured while the target app is in the foreground (before the Editor opens),
 * ensuring we get the target app's text, not the Editor's own UI.
 */
@Serializable
data class CapturedTextNode(
    val text: String,
    val boundsLeft: Float,
    val boundsTop: Float,
    val boundsRight: Float,
    val boundsBottom: Float
)
