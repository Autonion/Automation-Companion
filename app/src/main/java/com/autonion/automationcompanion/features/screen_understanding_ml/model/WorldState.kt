package com.autonion.automationcompanion.features.screen_understanding_ml.model

/**
 * The agent's internal understanding of the screen state.
 */
data class WorldState(
    val timestamp: Long,
    val visibleElements: List<UIElement>,
    val matchedAnchors: Map<String, UIElement>, // AnchorID -> Current Element
    val screenContext: String? = null // App package or Activity name if available
)
