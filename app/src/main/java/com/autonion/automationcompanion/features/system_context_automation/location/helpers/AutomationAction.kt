package com.autonion.automationcompanion.features.system_context_automation.location.helpers

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
}
