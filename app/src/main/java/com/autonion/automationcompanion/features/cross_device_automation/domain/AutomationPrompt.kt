package com.autonion.automationcompanion.features.cross_device_automation.domain

import kotlinx.serialization.Serializable

@Serializable
data class AutomationPrompt(
    val transactionId: String,
    val prompt: String,
    val timestamp: Long,
    val sourceDeviceId: String = "android_controller"
)
