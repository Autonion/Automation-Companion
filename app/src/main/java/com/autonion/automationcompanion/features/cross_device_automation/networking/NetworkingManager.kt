package com.autonion.automationcompanion.features.cross_device_automation.networking

import android.content.Context

import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRepository
import com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent
import com.autonion.automationcompanion.features.cross_device_automation.domain.PromptResponse
import com.autonion.automationcompanion.features.cross_device_automation.domain.ResponseStatus
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventReceiver
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class NetworkingManager(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val eventReceiver: EventReceiver,
    private val deviceAuthManager: com.autonion.automationcompanion.features.cross_device_automation.data.DeviceAuthManager
) {
    companion object {
        private const val TAG = "NetworkingManager"
    }

    interface NetworkingListener {
        fun onDeviceConnected(device: Device)
        fun onDeviceDisconnected(deviceId: String)
        fun onMessageReceived(deviceId: String, rawJson: String)
        /** Called once when the desktop agent sends its version in auth_result/connection_ack. Null = old agent. */
        fun onAgentVersionReceived(deviceId: String, agentVersion: String?, minCompanionVersion: String?) {}
        /** Called when desktop agent requests 6-digit PIN pairing. */
        fun onPairingRequired(deviceId: String, deviceName: String) {}
        /** Called when pairing succeeds and device is authenticated. */
        fun onPairingSuccess(deviceId: String) {}
        /** Called when pairing fails or is rejected. */
        fun onPairingFailed(deviceId: String, error: String) {}
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // Aggressive keep-alive to survive Doze
        .build()

    private val activeConnections = ConcurrentHashMap<String, WebSocket>()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>() // Tracks IP:port to prevent duplicate connections
    private val reconnectingDevices = ConcurrentHashMap.newKeySet<String>() // Prevents duplicate reconnect coroutines
    private val revocationCleanupDevices = ConcurrentHashMap.newKeySet<String>() // Ensures revoke cleanup runs once per device
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    private var collectionJob: kotlinx.coroutines.Job? = null
    var listener: NetworkingListener? = null

    // ── Two-Way Communication: Prompt Response Flow ──
    private val _responseFlow = MutableSharedFlow<PromptResponse>(extraBufferCapacity = 16)
    val responseFlow: SharedFlow<PromptResponse> = _responseFlow.asSharedFlow()

    fun hasActiveConnections(): Boolean = activeConnections.isNotEmpty()

    fun start() {
        if (collectionJob?.isActive == true) return

        collectionJob = scope.launch {
            deviceRepository.getSelectedDevices().collectLatest { selectedDevices ->
                // Disconnect from devices that are no longer selected
                val selectedIds = selectedDevices.map { it.id }.toSet()
                val toDisconnect = activeConnections.keys.filter { it !in selectedIds }
                for (deviceId in toDisconnect) {
                    val ws = activeConnections.remove(deviceId)
                    ws?.close(1000, "Device deselected")
                    // Clean up the endpoint tracking
                    scope.launch {
                        val device = deviceRepository.getDeviceById(deviceId)
                        if (device != null) {
                            connectedEndpoints.remove("${device.ipAddress}:${device.port}")
                        }
                    }
                    listener?.onDeviceDisconnected(deviceId)
                    Log.d(TAG, "Disconnected deselected device: $deviceId")
                }

                // Connect to newly selected devices
                selectedDevices.forEach { device ->
                    connectToDevice(device)
                }
            }
        }
    }

    fun submitPairingPin(deviceId: String, pin: String) {
        val ws = activeConnections[deviceId]
        if (ws != null) {
            val payload = gson.toJson(mapOf(
                "type" to "pairing_submit",
                "pin" to pin.trim(),
                "deviceId" to deviceAuthManager.deviceId,
                "deviceName" to deviceAuthManager.deviceName,
                "deviceSecret" to deviceAuthManager.deviceSecret
            ))
            ws.send(payload)
            Log.d(TAG, "Sent pairing_submit to device $deviceId")
        } else {
            Log.e(TAG, "Cannot submit PIN: no active connection for $deviceId")
        }
    }

    private fun connectToDevice(device: Device): Boolean {
        val endpoint = "${device.ipAddress}:${device.port}"
        // Synchronized check to prevent duplicate WebSocket creation
        synchronized(this) {
            if (activeConnections.containsKey(device.id) || connectedEndpoints.contains(endpoint)) {
                Log.d(TAG, "Skipping duplicate connection to ${device.name} ($endpoint)")
                return false
            }
            // Mark endpoint as connecting to block other attempts
            connectedEndpoints.add(endpoint)
        }

        val request = Request.Builder()
            .url("ws://${endpoint}/automation")
            .build()

        Log.d(TAG, "Connecting to ${device.name} at ${device.ipAddress}")
        DebugLogger.info(
            context, LogCategory.CROSS_DEVICE_SYNC,
            "WebSocket connection attempt",
            "Attempting to connect to ${device.name} at ws://${device.ipAddress}:${device.port}",
            TAG
        )

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to ${device.name}")
                DebugLogger.success(
                    context, LogCategory.CROSS_DEVICE_SYNC,
                    "WebSocket connected",
                    "Connected to ${device.name} at ws://${device.ipAddress}:${device.port}",
                    TAG
                )
                // Close any stale duplicate if one snuck through
                val oldWs = activeConnections.put(device.id, webSocket)
                if (oldWs != null && oldWs !== webSocket) {
                    Log.d(TAG, "Closing stale duplicate connection for ${device.name}")
                    oldWs.close(1000, "Replaced by new connection")
                }
                connectedEndpoints.add(endpoint)
                reconnectingDevices.remove(device.id)
                this@NetworkingManager.listener?.onDeviceConnected(device)

                // Send our full identity & version info to desktop
                try {
                    val appVersion = context.packageManager
                        .getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                    val clientInfo = gson.toJson(mapOf(
                        "type" to "client_info",
                        "app" to "AutomationCompanion",
                        "version" to appVersion,
                        "deviceId" to deviceAuthManager.deviceId,
                        "deviceName" to deviceAuthManager.deviceName,
                        "deviceSecret" to deviceAuthManager.deviceSecret
                    ))
                    webSocket.send(clientInfo)
                    Log.d(TAG, "Sent client_info v$appVersion (id=${deviceAuthManager.deviceId}) to ${device.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send client_info: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Forward raw message to listener (e.g. for Rule Triggers)
                this@NetworkingManager.listener?.onMessageReceived(device.id, text)
                DebugLogger.info(
                    context, LogCategory.CROSS_DEVICE_SYNC,
                    "Message received",
                    "From ${device.name}: $text",
                    TAG
                )

                try {
                    // 1. Parse as generic JsonObject first to check message type
                    val jsonObject = gson.fromJson(text, com.google.gson.JsonObject::class.java)
                    val type = jsonObject.get("type")?.asString

                    if (type == null) {
                        Log.w(TAG, "Received message without type: $text")
                        DebugLogger.warning(
                            context, LogCategory.CROSS_DEVICE_SYNC,
                            "Message without type",
                            "Received message from ${device.name} without a 'type' field: $text",
                            TAG
                        )
                        return
                    }

                    // 2. Handle Control & Authentication Messages
                    if (type == "auth_result" || type == "connection_ack") {
                        Log.d(TAG, "Auth response received from ${device.name}: $text")
                        DebugLogger.info(
                            context, LogCategory.CROSS_DEVICE_SYNC,
                            "Authentication Response",
                            "Received $type from ${device.name}: $text",
                            TAG
                        )

                        val status = jsonObject.get("status")?.asString ?: "unknown"
                        val agentId = jsonObject.get("agent_id")?.asString
                        val agentVersion = jsonObject.get("version")?.asString
                        val minCompanion = jsonObject.get("min_companion_version")?.asString

                        if (agentVersion != null) {
                            this@NetworkingManager.listener?.onAgentVersionReceived(
                                device.id, agentVersion, minCompanion
                            )
                        }

                        // Parse agent/prelogin to verify isServiceOnly
                        val agentField = jsonObject.get("agent")?.asString ?: ""
                        val preloginField = jsonObject.get("prelogin")?.asBoolean ?: false
                        val isServiceOnly = preloginField
                                || agentField.contains("prelogin", ignoreCase = true)

                        when (status) {
                            "authenticated", "connected" -> {
                                revocationCleanupDevices.remove(device.id)
                                if (!agentId.isNullOrBlank()) {
                                    deviceAuthManager.markAgentPaired(agentId)
                                }
                                scope.launch {
                                    val existing = deviceRepository.getDeviceById(device.id)
                                    if (existing != null) {
                                        deviceRepository.updateDevice(
                                            existing.copy(
                                                isPaired = true,
                                                isPairingRequired = false,
                                                agentId = agentId ?: existing.agentId,
                                                isServiceOnly = isServiceOnly
                                            )
                                        )
                                    }
                                }
                                this@NetworkingManager.listener?.onPairingSuccess(device.id)
                            }
                            "pairing_required" -> {
                                scope.launch {
                                    val existing = deviceRepository.getDeviceById(device.id)
                                    if (existing != null) {
                                        deviceRepository.updateDevice(
                                            existing.copy(
                                                isPaired = false,
                                                isPairingRequired = true,
                                                agentId = agentId ?: existing.agentId
                                            )
                                        )
                                    }
                                }
                                this@NetworkingManager.listener?.onPairingRequired(device.id, device.name)
                            }
                            "paired_success" -> {
                                revocationCleanupDevices.remove(device.id)
                                if (!agentId.isNullOrBlank()) {
                                    deviceAuthManager.markAgentPaired(agentId)
                                }
                                scope.launch {
                                    val existing = deviceRepository.getDeviceById(device.id)
                                    if (existing != null) {
                                        deviceRepository.updateDevice(
                                            existing.copy(
                                                isPaired = true,
                                                isPairingRequired = false,
                                                agentId = agentId ?: existing.agentId
                                            )
                                        )
                                    }
                                }
                                this@NetworkingManager.listener?.onPairingSuccess(device.id)
                            }
                            "pairing_failed" -> {
                                val error = jsonObject.get("error")?.asString ?: "Invalid PIN"
                                this@NetworkingManager.listener?.onPairingFailed(device.id, error)
                            }
                            "pairing_busy" -> {
                                val msg = jsonObject.get("message")?.asString ?: "Another pairing is in progress on this desktop."
                                this@NetworkingManager.listener?.onPairingFailed(device.id, msg)
                            }
                            "pairing_disabled" -> {
                                val msg = jsonObject.get("message")?.asString ?: "New device pairing is disabled on this desktop."
                                this@NetworkingManager.listener?.onPairingFailed(device.id, msg)
                            }
                            "pairing_expired" -> {
                                val msg = jsonObject.get("message")?.asString ?: "Pairing timed out."
                                this@NetworkingManager.listener?.onPairingFailed(device.id, msg)
                            }
                            "pairing_rejected" -> {
                                val msg = jsonObject.get("message")?.asString ?: "Pairing declined by desktop user."
                                this@NetworkingManager.listener?.onPairingFailed(device.id, msg)
                            }
                            "pairing_revoked" -> {
                                handlePairingRevoked(device.id)
                            }
                        }

                        return // Don't try to parse as RawEvent
                    }

                    // 3. Handle prompt/agent responses from Desktop.
                    if (type == "prompt_response" || type == "agent_step_result") {
                        val transactionId = jsonObject.get("transactionId")?.asString ?: ""
                        val rawStatus = jsonObject.get("status")?.asString ?: "unknown"
                        val status = when {
                            type == "agent_step_result" && rawStatus.equals("success", ignoreCase = true) ->
                                if (jsonObject.get("goalComplete")?.asBoolean == true) "completed" else "in_progress"
                            type == "agent_step_result" && rawStatus.equals("failed", ignoreCase = true) -> "failed"
                            else -> rawStatus
                        }
                        val message = jsonObject.get("message")?.asString
                            ?: jsonObject.get("action")?.asString
                            ?: ""

                        val responseStatus = try {
                            ResponseStatus.valueOf(status.uppercase())
                        } catch (_: Exception) {
                            ResponseStatus.IN_PROGRESS
                        }

                        // Parse optional data map (e.g. action_history for Save as Flow)
                        val dataMap: Map<String, String>? = try {
                            val dataObj = jsonObject.getAsJsonObject("data")
                            dataObj?.entrySet()?.associate { it.key to it.value.asString }
                        } catch (_: Exception) { null }

                        val response = PromptResponse(
                            transactionId = transactionId,
                            status = responseStatus,
                            message = message,
                            data = dataMap
                        )

                        scope.launch {
                            _responseFlow.emit(response)
                        }

                        Log.d(TAG, "Prompt response: $status - $message")
                        DebugLogger.info(
                            context, LogCategory.CROSS_DEVICE_SYNC,
                            "Desktop Response",
                            "[$status] $message (txn=$transactionId)",
                            TAG
                        )
                        return
                    }

                    // 4. Handle Control & Non-Data Messages (Early Exit)
                    if (type == "clipboard.sync_state_changed" ||
                        type == "clipboard.set_sync_enabled" ||
                        type == "clipboard.get_sync_state" ||
                        type == "rule_triggered" ||
                        type.startsWith("flow_")
                    ) {
                        // Handled by listener?.onMessageReceived
                        return
                    }

                    // 5. Handle Data Events (clipboard text/image sync, external events, etc.)
                    if (type == "clipboard.text_copied" ||
                        type == "clipboard.image_copied" ||
                        type.endsWith(".event") ||
                        type == "generic_event"
                    ) {
                        val event = parseRawEventSafely(jsonObject, device.id)
                        scope.launch {
                            eventReceiver.onEventReceived(event)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message: $text", e)
                    DebugLogger.error(
                        context, LogCategory.CROSS_DEVICE_SYNC,
                        "Message parsing failed",
                        "Failed to parse message from ${device.name}: $text. Error: ${e.message}",
                        TAG
                    )
                }
            }

            private fun parseRawEventSafely(jsonObject: com.google.gson.JsonObject, defaultDeviceId: String): RawEvent {
                val id = jsonObject.get("id")?.asString ?: java.util.UUID.randomUUID().toString()
                val type = jsonObject.get("type")?.asString ?: ""
                val sourceDeviceId = jsonObject.get("sourceDeviceId")?.asString
                    ?: jsonObject.get("source_device_id")?.asString
                    ?: defaultDeviceId

                val timestamp: Long = try {
                    val tsElement = jsonObject.get("timestamp")
                    when {
                        tsElement == null || tsElement.isJsonNull -> System.currentTimeMillis()
                        tsElement.isJsonPrimitive && tsElement.asJsonPrimitive.isNumber -> tsElement.asLong
                        tsElement.isJsonPrimitive && tsElement.asJsonPrimitive.isString -> {
                            val str = tsElement.asString
                            try {
                                str.toLong()
                            } catch (_: NumberFormatException) {
                                try {
                                    val cleanStr = str.substringBefore(".").substringBefore("Z")
                                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                                    sdf.parse(cleanStr)?.time ?: System.currentTimeMillis()
                                } catch (_: Exception) {
                                    System.currentTimeMillis()
                                }
                            }
                        }
                        else -> System.currentTimeMillis()
                    }
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }

                val payload = mutableMapOf<String, String>()
                try {
                    val payloadObj = jsonObject.getAsJsonObject("payload")
                    if (payloadObj != null) {
                        for (entry in payloadObj.entrySet()) {
                            val value = entry.value
                            if (value != null && !value.isJsonNull) {
                                payload[entry.key] = if (value.isJsonPrimitive) value.asString else value.toString()
                            }
                        }
                    }
                } catch (_: Exception) { }

                return RawEvent(
                    id = id,
                    timestamp = timestamp,
                    type = type,
                    sourceDeviceId = sourceDeviceId,
                    payload = payload
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing: $reason (code=$code)")
                DebugLogger.warning(
                    context, LogCategory.CROSS_DEVICE_SYNC,
                    "WebSocket closing",
                    "Connection to ${device.name} closing. Code: $code, Reason: $reason",
                    TAG
                )
                webSocket.close(1000, null)
                handleDisconnection(device.id, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closed: $reason (code=$code)")
                handleDisconnection(device.id, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failure: ${t.message}")
                DebugLogger.error(
                    context, LogCategory.CROSS_DEVICE_SYNC,
                    "WebSocket failure",
                    "Connection to ${device.name} failed: ${t.message}",
                    TAG
                )
                handleDisconnection(device.id, 1006, t.message ?: "Connection failure")
                // Auto-reconnect if this device is still selected
                scheduleReconnect(device)
            }
        }

        client.newWebSocket(request, listener)
        return true
    }

    private fun handleDisconnection(deviceId: String, code: Int, reason: String) {
        val wasConnected = activeConnections.remove(deviceId) != null
        reconnectingDevices.remove(deviceId)
        scope.launch {
            val device = deviceRepository.getDeviceById(deviceId)
            if (device != null) {
                connectedEndpoints.remove("${device.ipAddress}:${device.port}")
            }
        }
        if (code == 4001) {
            handlePairingRevoked(deviceId)
        }
        if (wasConnected) {
            this@NetworkingManager.listener?.onDeviceDisconnected(deviceId)
        }
    }

    fun disconnectDevice(deviceId: String) {
        val ws = activeConnections[deviceId]
        ws?.close(1000, "Device unpaired")
        handleDisconnection(deviceId, 1000, "Device unpaired")
    }

    private fun handlePairingRevoked(deviceId: String) {
        if (!revocationCleanupDevices.add(deviceId)) {
            Log.d(TAG, "Pairing revocation cleanup already handled for $deviceId")
            return
        }

        Log.i(TAG, "Device $deviceId pairing revoked")
        scope.launch {
            deviceAuthManager.rotateSecret()
            val device = deviceRepository.getDeviceById(deviceId)
            if (device != null) {
                device.agentId?.let { deviceAuthManager.unpairAgent(it) }
                deviceRepository.updateDevice(
                    device.copy(
                        isPaired = false,
                        isPairingRequired = true,
                        isSelected = false,
                        agentId = null
                    )
                )
            }
        }
        this@NetworkingManager.listener?.onPairingFailed(deviceId, "Pairing revoked by desktop host.")
    }

    fun sendCommand(deviceId: String, command: Any) {
        val webSocket = activeConnections[deviceId]
        if (webSocket != null) {
            val json = gson.toJson(command)
            webSocket.send(json)
        } else {
            Log.e("NetworkingManager", "No active connection for device $deviceId")
        }
    }

    fun broadcast(event: Any) {
        val json = gson.toJson(event)
        val connections = activeConnections.values
        Log.d("NetworkingManager", "Broadcasting event to ${connections.size} devices: $json")

        connections.forEach { webSocket ->
            try {
                webSocket.send(json)
            } catch (e: Exception) {
                Log.e("NetworkingManager", "Failed to broadcast to device", e)
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null

        activeConnections.values.forEach { it.close(1000, "Shutting down") }
        activeConnections.clear()
        connectedEndpoints.clear()

        // Do NOT shutdown executor as client is reused.
        client.dispatcher.cancelAll()
    }

    private fun scheduleReconnect(device: Device) {
        // Prevent multiple concurrent reconnect loops for the same device
        if (!reconnectingDevices.add(device.id)) {
            Log.d(TAG, "Reconnect already in progress for ${device.name}, skipping")
            return
        }

        scope.launch {
            var attempt = 0
            val maxDelay = 30_000L // 30 seconds max
            try {
                while (collectionJob?.isActive == true) {
                    // Check if device is still selected before reconnecting
                    val selectedDevices = deviceRepository.getSelectedDevices().first()
                    val stillSelected = selectedDevices.any { it.id == device.id }
                    if (!stillSelected) {
                        Log.d(TAG, "Device ${device.name} no longer selected, stopping reconnect")
                        return@launch
                    }
                    // Already reconnected by another path
                    if (activeConnections.containsKey(device.id)) {
                        Log.d(TAG, "Device ${device.name} already reconnected")
                        return@launch
                    }

                    val delayMs = minOf((2000L * (1 shl attempt)), maxDelay)
                    Log.d(TAG, "Reconnecting to ${device.name} in ${delayMs}ms (attempt ${attempt + 1})")
                    delay(delayMs)

                    // Re-check after delay
                    val stillSelectedAfterDelay = deviceRepository.getSelectedDevices().first().any { it.id == device.id }
                    if (!stillSelectedAfterDelay || activeConnections.containsKey(device.id)) {
                        return@launch
                    }

                    // Fetch latest device info (IP may have changed)
                    val latestDevice = deviceRepository.getDeviceById(device.id) ?: return@launch
                    Log.d(TAG, "Attempting reconnect to ${latestDevice.name} at ${latestDevice.ipAddress}:${latestDevice.port}")
                    val connected = connectToDevice(latestDevice)

                    if (!connected) {
                        // connectToDevice was blocked (already connecting), just wait
                        delay(3000)
                    } else {
                        // Wait a bit to see if connection succeeds
                        delay(3000)
                    }
                    if (activeConnections.containsKey(device.id)) {
                        Log.d(TAG, "Reconnect to ${device.name} succeeded")
                        return@launch
                    }
                    attempt++
                }
            } finally {
                reconnectingDevices.remove(device.id)
            }
        }
    }

}

