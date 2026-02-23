package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.RectF
import kotlinx.serialization.Serializable

// ActionType is defined in ActionIntent.kt

@Serializable
data class AutomationStep(
    val id: String,
    var orderIndex: Int,
    val label: String, // e.g., "Login Button"
    val actionType: ActionType = ActionType.CLICK,
    val anchor: UIElement, // The visual anchor for this step
    val inputText: String? = null,
    val isOptional: Boolean = false
)
