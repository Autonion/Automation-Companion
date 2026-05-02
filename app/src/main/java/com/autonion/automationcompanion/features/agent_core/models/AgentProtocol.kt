package com.autonion.automationcompanion.features.agent_core.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentRequest(
    val type: String = "agent_request",
    val schemaVersion: Int = 1,
    val transactionId: String,
    val prompt: String,
    val timestamp: Long,
    val sourceDeviceId: String = "android_controller",
    val target: String = "desktop",
    val context: String? = null,
    val agentContext: AgentRequestContext? = null,
    val limits: AgentLimits = AgentLimits(),
    val safety: AgentSafety = AgentSafety(),
    val capabilitiesRequired: List<String> = DEFAULT_CAPABILITIES
) {
    companion object {
        val DEFAULT_CAPABILITIES = listOf(
            "ui_observation",
            "click",
            "type",
            "hotkey",
            "scroll",
            "verification"
        )
    }
}

@Serializable
data class AgentRequestContext(
    val conversationSummary: String? = null,
    val preferredModelMode: String? = null,
    val origin: String = "android"
)

@Serializable
data class AgentLimits(
    val maxSteps: Int = 15,
    val stepTimeoutMs: Long = 45_000L,
    val overallTimeoutMs: Long = 300_000L
)

@Serializable
data class AgentSafety(
    val allowDestructive: Boolean = false,
    val requireConfirmationForRisky: Boolean = true,
    val redactSensitiveTextForCloud: Boolean = true
)

@Serializable
data class AgentStepResult(
    val type: String = "agent_step_result",
    val transactionId: String,
    val step: Int,
    val status: String,
    val message: String? = null,
    val action: String? = null,
    val errorCode: String? = null,
    val goalComplete: Boolean = false
)

