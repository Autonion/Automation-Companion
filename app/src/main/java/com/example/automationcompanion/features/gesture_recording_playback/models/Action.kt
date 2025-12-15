package com.example.automationcompanion.features.gesture_recording_playback.models

import android.graphics.PointF
import kotlinx.serialization.Serializable

@Serializable
data class Action(
    val id: Int,
    val type: ActionType,
    val points: List<@Serializable(with = PointFSerializer::class) PointF> = emptyList(),
    val duration: Long = 100,
    val delayBefore: Long = 0,
    val delayAfter: Long = 500,
    val isEnabled: Boolean = true
)
