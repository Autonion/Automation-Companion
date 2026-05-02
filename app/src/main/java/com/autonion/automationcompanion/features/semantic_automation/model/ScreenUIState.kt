package com.autonion.automationcompanion.features.semantic_automation.model

import android.graphics.RectF
import kotlinx.serialization.Serializable
import com.autonion.automationcompanion.features.screen_understanding_ml.model.RectFSerializer

/**
 * A single element in the structured UI state graph.
 * This is the common representation regardless of whether the element
 * came from the Accessibility tree or YOLO + OCR pipeline.
 */
@Serializable
data class UIStateElement(
    val id: String,
    val type: String,           // e.g. "button", "input", "icon", "text", "toggle", "checkbox", "dropdown"
    val text: String? = null,   // Visible text / content description
    @Serializable(with = RectFSerializer::class)
    val bounds: RectF,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEditable: Boolean = false,
    val isChecked: Boolean? = null,  // For toggles, checkboxes, radios
    val className: String? = null,   // Android view class name if from accessibility
    val resourceId: String? = null,
    val contentDescription: String? = null,
    val hierarchyPath: String? = null,
    val isEnabled: Boolean = true,
    val isFocused: Boolean = false,
    val confidence: Float = 1.0f,    // 1.0 for accessibility, model confidence for YOLO
    val source: ElementSource = ElementSource.ACCESSIBILITY
)

enum class ElementSource {
    ACCESSIBILITY,
    YOLO_OCR,
    EXTENSION_DOM    // DOM elements from the browser extension bridge
}

/**
 * The structured UI state for one screen snapshot.
 * Fed into the Action Predictor along with the [SemanticGoal].
 */
@Serializable
data class ScreenUIState(
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String? = null,
    val activityName: String? = null,
    val elements: List<UIStateElement> = emptyList(),
    val source: ElementSource = ElementSource.ACCESSIBILITY // Primary source used
)
