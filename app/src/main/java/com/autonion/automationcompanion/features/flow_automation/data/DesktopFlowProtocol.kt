package com.autonion.automationcompanion.features.flow_automation.data

/**
 * Protocol models for Desktop ↔ Android flow communication over WebSocket.
 *
 * These match the JSON contracts defined in the Desktop's `connection_provider.dart`:
 * - `trigger_flow` / `flow_trigger_response`
 * - `list_flows` / `flow_list_response`
 */

// ── Outgoing: Android → Desktop ────────────────────────────────────

/** Request the desktop to list all saved flows. */
data class ListFlowsRequest(
    val type: String = "list_flows",
    val transactionId: String
)

/** Request the desktop to trigger (execute) a flow by ID. */
data class TriggerFlowRequest(
    val type: String = "trigger_flow",
    val transactionId: String,
    val flowId: String
)

/** Request the desktop to stop the currently running flow. */
data class StopFlowRequest(
    val type: String = "stop_flow",
    val transactionId: String,
    val flowId: String
)

// ── Incoming: Desktop → Android ────────────────────────────────────

/** A lightweight manifest of a Desktop flow (mirrors Desktop's FlowManifest). */
data class DesktopFlowManifest(
    val id: String,
    val name: String,
    val description: String = "",
    val nodeCount: Int = 0,
    val triggerType: String = "manual",
    val version: Int = 1,
    val updatedAt: String = ""
)

/** Response status for flow trigger operations. */
enum class FlowTriggerStatus {
    STARTED,
    STEP_EXECUTING,
    STEP_COMPLETED,
    COMPLETED,
    STOPPED,
    FAILED;

    companion object {
        fun fromString(value: String): FlowTriggerStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: FAILED
    }
}

/** Progress update from a running Desktop flow. */
data class FlowTriggerProgress(
    val flowId: String,
    val status: FlowTriggerStatus,
    val message: String,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val nodeLabel: String? = null
)
