package com.autonion.automationcompanion.features.flow_automation.data

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Flow results belong to a peer and transaction; losing a socket never proves completion. */
class DesktopFlowManager(private val context: Context, private val crossDeviceManager: CrossDeviceAutomationManager) {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _desktopFlows = MutableStateFlow<List<DesktopFlowManifest>>(emptyList())
    val desktopFlows: StateFlow<List<DesktopFlowManifest>> = _desktopFlows.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _runningFlowId = MutableStateFlow<String?>(null)
    val runningFlowId: StateFlow<String?> = _runningFlowId.asStateFlow()
    private val _progressUpdates = MutableSharedFlow<FlowTriggerProgress>(extraBufferCapacity = 32)
    val progressUpdates: SharedFlow<FlowTriggerProgress> = _progressUpdates.asSharedFlow()

    private data class Pending(val deviceId: String, val purpose: String, val flowId: String? = null, var timeout: Job? = null)
    private val pending = mutableMapOf<String, Pending>()
    private var runningTransaction: String? = null

    @Synchronized
    fun requestFlowList(deviceId: String? = null) {
        val peers = deviceId?.let { setOf(it) } ?: crossDeviceManager.networkingManager.connectedDeviceIds()
        for (peer in peers) {
            pending.filterValues { it.deviceId == peer && it.purpose == "list" }.keys.toList().forEach { remove(it) }
            val txn = UUID.randomUUID().toString()
            pending[txn] = Pending(peer, "list")
            if (!crossDeviceManager.networkingManager.sendCommand(peer, ListFlowsRequest(transactionId = txn))) {
                remove(txn); continue
            }
            armTimeout(txn, 20_000)
        }
        refreshLoading()
    }

    @Synchronized
    fun triggerFlow(flowId: String) {
        if (runningTransaction != null) return
        val peer = _desktopFlows.value.firstOrNull { it.id == flowId }?.deviceId
            ?.takeIf { it.isNotBlank() } ?: crossDeviceManager.networkingManager.connectedDeviceIds().firstOrNull()
        if (peer == null) { emitFailure(flowId, "No desktop connected"); return }
        val txn = UUID.randomUUID().toString()
        pending[txn] = Pending(peer, "trigger", flowId)
        runningTransaction = txn; _runningFlowId.value = flowId
        if (!crossDeviceManager.networkingManager.sendCommand(peer, TriggerFlowRequest(transactionId = txn, flowId = flowId))) {
            remove(txn); emitFailure(flowId, "Could not send the flow request"); return
        }
        armTimeout(txn, 20_000)
    }

    @Synchronized
    fun stopFlow(flowId: String) {
        val running = pending[runningTransaction] ?: return
        val txn = UUID.randomUUID().toString()
        pending[txn] = Pending(running.deviceId, "stop", flowId)
        if (!crossDeviceManager.networkingManager.sendCommand(running.deviceId, StopFlowRequest(transactionId = txn, flowId = flowId))) {
            remove(txn); emitFailure(flowId, "Connection lost; stopping the desktop flow could not be confirmed")
        } else armTimeout(txn, 20_000)
    }

    @Synchronized
    fun onDeviceDisconnected(deviceId: String? = null) {
        pending.filterValues { deviceId == null || it.deviceId == deviceId }.keys.toList().forEach { txn ->
            val request = pending[txn] ?: return@forEach
            if (request.purpose == "trigger") emitFailure(request.flowId.orEmpty(), "Connection lost; the flow outcome could not be confirmed")
            remove(txn)
        }
        _desktopFlows.value = if (deviceId == null) emptyList() else _desktopFlows.value.filterNot { it.deviceId == deviceId }
        refreshLoading()
    }

    @Synchronized
    fun handleIncomingMessage(rawJson: String, deviceId: String? = null): Boolean {
        try {
            val map: Map<String, Any> = gson.fromJson(rawJson, object : TypeToken<Map<String, Any>>() {}.type)
            val type = map["type"]?.toString()
            if (type != "flow_list_response" && type != "flow_trigger_response") return false
            val txn = map["transactionId"]?.toString() ?: return true
            val request = pending[txn] ?: return true
            if (deviceId != null && request.deviceId != deviceId) return true
            if (type == "flow_list_response" && request.purpose == "list") {
                @Suppress("UNCHECKED_CAST")
                val flows = if (map["error"] != null) emptyList() else
                    (map["flows"] as? List<Map<String, Any>>).orEmpty().map { flow ->
                        DesktopFlowManifest(id = flow["id"]?.toString().orEmpty(), name = flow["name"]?.toString() ?: "Unnamed",
                            description = flow["description"]?.toString().orEmpty(), nodeCount = (flow["nodeCount"] as? Number)?.toInt() ?: 0,
                            triggerType = flow["triggerType"]?.toString() ?: "manual", version = (flow["version"] as? Number)?.toInt() ?: 1,
                            updatedAt = flow["updatedAt"]?.toString().orEmpty(), deviceId = request.deviceId)
                    }
                _desktopFlows.value = _desktopFlows.value.filterNot { it.deviceId == request.deviceId } + flows
                remove(txn)
            } else if (type == "flow_trigger_response" && request.purpose != "list") {
                val flowId = map["flowId"]?.toString().orEmpty()
                if (flowId != request.flowId) return true
                val status = FlowTriggerStatus.fromString(map["status"]?.toString().orEmpty())
                val progress = FlowTriggerProgress(flowId, status, map["message"]?.toString().orEmpty(),
                    (map["currentStep"] as? Number)?.toInt() ?: 0, (map["totalSteps"] as? Number)?.toInt() ?: 0,
                    map["nodeLabel"]?.toString())
                scope.launch { _progressUpdates.emit(progress) }
                val terminal = status in setOf(FlowTriggerStatus.COMPLETED, FlowTriggerStatus.FAILED, FlowTriggerStatus.STOPPED)
                if (terminal) {
                    remove(txn)
                    if (request.purpose == "stop" && status == FlowTriggerStatus.STOPPED) {
                        runningTransaction?.takeIf { pending[it]?.deviceId == request.deviceId && pending[it]?.flowId == flowId }?.let { remove(it) }
                    }
                } else armTimeout(txn, 120_000)
            }
            return true
        } catch (e: Exception) { Log.e("DesktopFlowManager", "Invalid flow response", e); return false }
    }

    private fun emitFailure(flowId: String, message: String) {
        scope.launch { _progressUpdates.emit(FlowTriggerProgress(flowId, FlowTriggerStatus.FAILED, message)) }
    }

    private fun armTimeout(txn: String, delayMs: Long) {
        val request = pending[txn] ?: return
        request.timeout?.cancel()
        request.timeout = scope.launch {
            delay(delayMs)
            synchronized(this@DesktopFlowManager) {
                if (pending[txn] !== request) return@synchronized
                if (request.purpose != "list") emitFailure(request.flowId.orEmpty(), "No response from desktop; the flow outcome could not be confirmed")
                remove(txn)
            }
        }
    }

    private fun remove(txn: String) {
        pending.remove(txn)?.timeout?.cancel()
        if (runningTransaction == txn) { runningTransaction = null; _runningFlowId.value = null }
        refreshLoading()
    }
    private fun refreshLoading() { _isLoading.value = pending.values.any { it.purpose == "list" } }
}
