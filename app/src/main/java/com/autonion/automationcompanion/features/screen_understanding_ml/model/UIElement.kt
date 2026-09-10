package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.RectF
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

/**
 * Represents a detected UI element on the screen.
 */
@Serializable
data class UIElement(
    @field:SerializedName("id")
    val id: String, // Unique tracking ID
    @field:SerializedName("label")
    val label: String, // Class name (e.g., "Button", "Input")
    @field:SerializedName("confidence")
    val confidence: Float,
    @Serializable(with = RectFSerializer::class)
    @field:SerializedName("bounds")
    val bounds: RectF, // Bounding box relative to screen
    @field:SerializedName("text")
    val text: String? = null, // OCR text if available
    @field:SerializedName("visualFingerprint")
    val visualFingerprint: String? = null, // Hash or embedding of visual appearance
    @field:SerializedName("lastSeenTimestamp")
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    @field:SerializedName("source")
    val source: String = "yolo" // "yolo" or "accessibility" — indicates detection origin
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
