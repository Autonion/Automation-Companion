package com.autonion.automationcompanion.features.cross_device_automation.domain

import kotlinx.serialization.Serializable

@Serializable
data class AutomationRule(
    val id: String,
    val name: String,
    val isEnabled: Boolean = true,
    val trigger: RuleTrigger,
    val conditions: List<RuleCondition> = emptyList(),
    val actions: List<RuleAction>,
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL
)

@Serializable
data class RuleTrigger(
    val eventType: String
)

@Serializable
sealed class RuleCondition {
    @Serializable
    data class TagContains(val tag: String) : RuleCondition()
    
    @Serializable
    data class PayloadContains(val key: String, val value: String) : RuleCondition()
    
    @Serializable
    data class SourceDevice(val deviceId: String) : RuleCondition()
}

@Serializable
data class RuleAction(
    val type: String, // e.g., "open_url", "enable_dnd"
    val parameters: Map<String, String> = emptyMap(),
    val targetDeviceId: String? = null // null means local or broadcast, specific logic needed
)

enum class ExecutionMode {
    SEQUENTIAL,
    PARALLEL
}
