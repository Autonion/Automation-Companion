package com.autonion.automationcompanion.features.flow_automation.data

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages Desktop Flow interactions from Android.
 *
 * Responsibilities:
 * - Request the list of desktop flows via WebSocket (`list_flows`)
 * - Trigger a desktop flow by ID (`trigger_flow`)
 * - Parse incoming `flow_list_response` and `flow_trigger_response` messages
 * - Expose state flows for UI consumption
 */
class DesktopFlowManager(
    private val context: Context,
    private val crossDeviceManager: CrossDeviceAutomationManager
) {
    companion object {
        private const val TAG = "DesktopFlowManager"
    }

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    // ── State ──────────────────────────────────────────────────

    /** Desktop flows fetched from the connected host. */
    private val _desktopFlows = MutableStateFlow<List<DesktopFlowManifest>>(emptyList())
    val desktopFlows: StateFlow<List<DesktopFlowManifest>> = _desktopFlows.asStateFlow()

    /** Whether we are currently fetching flows from desktop. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** The ID of the flow currently being executed on the desktop (if any). */
    private val _runningFlowId = MutableStateFlow<String?>(null)
    val runningFlowId: StateFlow<String?> = _runningFlowId.asStateFlow()

    /** Real-time progress updates from the executing flow. */
    private val _progressUpdates = MutableSharedFlow<FlowTriggerProgress>(extraBufferCapacity = 32)
    val progressUpdates: SharedFlow<FlowTriggerProgress> = _progressUpdates.asSharedFlow()

    /** Pending transaction IDs we are waiting for responses. */
    private val pendingTransactions = mutableMapOf<String, String>() // txnId → purpose

    // ── Outgoing Commands ──────────────────────────────────────

    /**
     * Request desktop to list all saved flows.
     * The response arrives asynchronously via [handleIncomingMessage].
     */
    fun requestFlowList() {
        if (!crossDeviceManager.networkingManager.hasActiveConnections()) {
            Log.w(TAG, "No desktop connected — cannot list flows")
            _desktopFlows.value = emptyList()
            return
        }

        val transactionId = UUID.randomUUID().toString()
        pendingTransactions[transactionId] = "list_flows"

        val request = ListFlowsRequest(transactionId = transactionId)
        crossDeviceManager.networkingManager.broadcast(request)

        _isLoading.value = true
        Log.d(TAG, "Requested flow list (txn=$transactionId)")
        DebugLogger.info(
            context, LogCategory.CROSS_DEVICE_SYNC,
            "Desktop Flows", "Requesting flow list from desktop",
            TAG
        )
    }

    /**
     * Trigger a desktop flow by ID.
     * Progress is streamed back via [progressUpdates].
     */
    fun triggerFlow(flowId: String) {
        if (!crossDeviceManager.networkingManager.hasActiveConnections()) {
            Log.w(TAG, "No desktop connected — cannot trigger flow")
            scope.launch {
                _progressUpdates.emit(
                    FlowTriggerProgress(
                        flowId = flowId,
                        status = FlowTriggerStatus.FAILED,
                        message = "No desktop connected"
                    )
                )
            }
            return
        }

        val transactionId = UUID.randomUUID().toString()
        pendingTransactions[transactionId] = "trigger_flow"

        val request = TriggerFlowRequest(
            transactionId = transactionId,
            flowId = flowId
        )

        _runningFlowId.value = flowId
        crossDeviceManager.networkingManager.broadcast(request)

        Log.d(TAG, "Triggered flow $flowId (txn=$transactionId)")
        DebugLogger.info(
            context, LogCategory.CROSS_DEVICE_SYNC,
            "Desktop Flows", "Triggering flow: $flowId",
            TAG
        )
    }

    // ── Incoming Message Handler ───────────────────────────────

    /**
     * Called by the networking layer when a message arrives from Desktop.
     * Checks if it's a flow-related response and processes it.
     *
     * @return true if the message was handled (flow-related), false otherwise.
     */
    fun handleIncomingMessage(rawJson: String): Boolean {
        try {
            val mapType = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(rawJson, mapType)
            val type = map["type"]?.toString() ?: return false

            return when (type) {
                "flow_list_response" -> {
                    handleFlowListResponse(map)
                    true
                }
                "flow_trigger_response" -> {
                    handleFlowTriggerResponse(map)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming message", e)
            return false
        }
    }

    private fun handleFlowListResponse(map: Map<String, Any>) {
        _isLoading.value = false

        val error = map["error"]?.toString()
        if (error != null) {
            Log.e(TAG, "Flow list error from desktop: $error")
            _desktopFlows.value = emptyList()
            return
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val flowsJson = map["flows"] as? List<Map<String, Any>> ?: emptyList()

            val manifests = flowsJson.map { flowMap ->
                DesktopFlowManifest(
                    id = flowMap["id"]?.toString() ?: "",
                    name = flowMap["name"]?.toString() ?: "Unnamed",
                    description = flowMap["description"]?.toString() ?: "",
                    nodeCount = (flowMap["nodeCount"] as? Double)?.toInt() ?: 0,
                    triggerType = flowMap["triggerType"]?.toString() ?: "manual",
                    version = (flowMap["version"] as? Double)?.toInt() ?: 1,
                    updatedAt = flowMap["updatedAt"]?.toString() ?: ""
                )
            }

            _desktopFlows.value = manifests
            Log.d(TAG, "Received ${manifests.size} desktop flows")
            DebugLogger.info(
                context, LogCategory.CROSS_DEVICE_SYNC,
                "Desktop Flows", "Received ${manifests.size} flows from desktop",
                TAG
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse flow list response", e)
            _desktopFlows.value = emptyList()
        }
    }

    private fun handleFlowTriggerResponse(map: Map<String, Any>) {
        val flowId = map["flowId"]?.toString() ?: ""
        val statusStr = map["status"]?.toString() ?: "failed"
        val message = map["message"]?.toString() ?: ""
        val currentStep = (map["currentStep"] as? Double)?.toInt() ?: 0
        val totalSteps = (map["totalSteps"] as? Double)?.toInt() ?: 0
        val nodeLabel = map["nodeLabel"]?.toString()

        val status = FlowTriggerStatus.fromString(statusStr)

        val progress = FlowTriggerProgress(
            flowId = flowId,
            status = status,
            message = message,
            currentStep = currentStep,
            totalSteps = totalSteps,
            nodeLabel = nodeLabel
        )

        scope.launch {
            _progressUpdates.emit(progress)
        }

        // Clear running state on terminal statuses
        if (status == FlowTriggerStatus.COMPLETED || status == FlowTriggerStatus.FAILED) {
            _runningFlowId.value = null
            Log.d(TAG, "Flow $flowId finished: $statusStr - $message")
        }

        DebugLogger.info(
            context, LogCategory.CROSS_DEVICE_SYNC,
            "Desktop Flow Progress",
            "[$statusStr] $message (step $currentStep/$totalSteps)",
            TAG
        )
    }
}
