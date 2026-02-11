package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.PointF

enum class ActionType {
    CLICK,
    SCROLL_UP,
    SCROLL_DOWN,
    INPUT_TEXT,
    WAIT,
    FINISH,
    FAIL
}

data class ActionIntent(
    val type: ActionType,
    val targetId: String? = null, // ID of the UIElement to interact with
    val targetPoint: PointF? = null, // Specific coordinates if needed
    val inputText: String? = null,
    val description: String
)
