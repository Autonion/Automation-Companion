package com.autonion.automationcompanion.features.agent_core.models

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.Serializable

// WebSocket requests use Gson; Kotlinx annotations alone do not preserve their field names.
@Serializable
data class AgentRequest(
    @field:SerializedName("type")
    val type: String = "agent_request",
    @field:SerializedName("schemaVersion")
    val schemaVersion: Int = 1,
    @field:SerializedName("transactionId")
    val transactionId: String,
    @field:SerializedName("prompt")
    val prompt: String,
    @field:SerializedName("timestamp")
    val timestamp: Long,
    @field:SerializedName("sourceDeviceId")
    val sourceDeviceId: String = "android_controller",
    @field:SerializedName("target")
    val target: String = "desktop",
    @field:SerializedName("context")
    val context: String? = null,
    @field:SerializedName("agentContext")
    val agentContext: AgentRequestContext? = null,
    @field:SerializedName("conversationHistory")
    val conversationHistory: List<Map<String, String>> = emptyList(),
    @field:SerializedName("limits")
    val limits: AgentLimits = AgentLimits(),
    @field:SerializedName("safety")
    val safety: AgentSafety = AgentSafety(),
    @field:SerializedName("capabilitiesRequired")
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
    @field:SerializedName("conversationSummary")
    val conversationSummary: String? = null,
    @field:SerializedName("preferredModelMode")
    val preferredModelMode: String? = null,
    @field:SerializedName("origin")
    val origin: String = "android"
)

@Serializable
data class AgentLimits(
    @field:SerializedName("maxSteps")
    val maxSteps: Int = 15,
    @field:SerializedName("stepTimeoutMs")
    val stepTimeoutMs: Long = 45_000L,
    @field:SerializedName("overallTimeoutMs")
    val overallTimeoutMs: Long = 300_000L
)

@Serializable
data class AgentSafety(
    @field:SerializedName("allowDestructive")
    val allowDestructive: Boolean = false,
    @field:SerializedName("requireConfirmationForRisky")
    val requireConfirmationForRisky: Boolean = true,
    @field:SerializedName("redactSensitiveTextForCloud")
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

