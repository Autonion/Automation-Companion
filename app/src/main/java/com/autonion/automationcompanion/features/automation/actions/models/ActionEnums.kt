package com.autonion.automationcompanion.features.automation.actions.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RingerMode {
    @SerialName("normal") NORMAL,
    @SerialName("vibrate") VIBRATE,
    @SerialName("silent") SILENT
}

@Serializable
enum class AppActionType {
    @SerialName("launch") LAUNCH,
    @SerialName("info") INFO
}

@Serializable
enum class NotificationType {
    @SerialName("normal") NORMAL,
    @SerialName("silent") SILENT,
    @SerialName("ongoing") ONGOING,
    @SerialName("reminder") REMINDER
}