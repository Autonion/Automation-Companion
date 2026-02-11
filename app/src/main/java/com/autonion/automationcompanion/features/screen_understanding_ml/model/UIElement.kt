package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.RectF

/**
 * Represents a detected UI element on the screen.
 */
data class UIElement(
    val id: String, // Unique tracking ID
    val label: String, // Class name (e.g., "Button", "Input")
    val confidence: Float,
    val bounds: RectF, // Bounding box relative to screen
    val text: String? = null, // OCR text if available
    val visualFingerprint: String? = null, // Hash or embedding of visual appearance
    val lastSeenTimestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UIElement

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
