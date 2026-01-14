package com.autonion.automationcompanion.features.automation.actions.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * AutomationAction represents an executable action that can be triggered by any system context.
 * These are the final, validated actions ready to execute.
 * Trigger-agnostic: location, battery, app state, etc. can all use these actions.
 */
@Serializable
sealed class AutomationAction {

    @Serializable
    @SerialName("send_sms")
    data class SendSms(
        val message: String,
        val contactsCsv: String
    ) : AutomationAction()

    @Serializable
    @SerialName("set_volume")
    data class SetVolume(
        val ring: Int,
        val media: Int
    ) : AutomationAction()

    @Serializable
    @SerialName("set_brightness")
    data class SetBrightness(
        val level: Int
    ) : AutomationAction()

    @Serializable
    @SerialName("set_dnd")
    data class SetDnd(
        val enabled: Boolean
    ) : AutomationAction()

    // ───────────── Display Actions ─────────────

    @Serializable
    @SerialName("set_dark_mode")
    data class SetDarkMode(
        val enabled: Boolean
    ) : AutomationAction()

    @Serializable
    @SerialName("set_auto_rotate")
    data class SetAutoRotate(
        val enabled: Boolean
    ) : AutomationAction()

    @Serializable
    @SerialName("set_screen_timeout")
    data class SetScreenTimeout(
        val durationMs: Int
    ) : AutomationAction()

    @Serializable
    @SerialName("set_night_light")
    data class SetNightLight(
        val enabled: Boolean
    ) : AutomationAction()

    @Serializable
    @SerialName("set_keep_screen_awake")
    data class SetKeepScreenAwake(
        val enabled: Boolean
    ) : AutomationAction()
}
