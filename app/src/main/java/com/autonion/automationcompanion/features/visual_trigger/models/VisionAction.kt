package com.autonion.automationcompanion.features.visual_trigger.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class VisionAction {
    @Serializable
    @SerialName("click")
    object Click : VisionAction()

    @Serializable
    @SerialName("long_click")
    object LongClick : VisionAction()

    @Serializable
    @SerialName("scroll")
    data class Scroll(val direction: ScrollDirection) : VisionAction()
}

@Serializable
enum class ScrollDirection {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT
}
