package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.RectF

/**
 * A persistent reference to a UI element used for resilient matching.
 */
data class Anchor(
    val id: String,
    val description: String, // User-friendly name
    val targetClass: String, // Expected class label
    val targetText: String?, // Expected text content
    val relativePosition: RectF, // Normalized position (0.0-1.0)
    val spatialRelationships: Map<String, String> = emptyMap() // e.g., "above" -> "submit_button"
)
