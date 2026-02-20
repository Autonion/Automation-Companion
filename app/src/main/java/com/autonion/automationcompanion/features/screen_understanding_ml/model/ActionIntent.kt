package com.autonion.automationcompanion.features.screen_understanding_ml.model

import android.graphics.PointF
import kotlinx.serialization.Serializable
import com.autonion.automationcompanion.features.gesture_recording_playback.models.PointFSerializer

@Serializable
enum class ActionType {
    CLICK,
    SCROLL_UP,
    SCROLL_DOWN,
    INPUT_TEXT,
    WAIT,
    FINISH,
    FAIL
}

@Serializable
data class ActionIntent(
    val type: ActionType,
    val targetId: String? = null, // ID of the UIElement to interact with
    @Serializable(with = PointFSerializer::class)
    val targetPoint: PointF? = null, // Specific coordinates if needed
    val inputText: String? = null,
    val description: String
)
