package com.autonion.automationcompanion.features.gesture_recording_playback.models

import kotlinx.serialization.Serializable

@Serializable
enum class ActionType {
    CLICK,
    SWIPE,
    LONG_CLICK,
    WAIT
}
