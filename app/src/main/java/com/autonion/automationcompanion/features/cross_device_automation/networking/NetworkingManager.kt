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
    private val eventReceiver: EventReceiver
) {
    companion object {
        private const val TAG = "NetworkingManager"
    }

    interface NetworkingListener {
        fun onDeviceConnected(device: Device)
        fun onDeviceDisconnected(deviceId: String)
        fun onMessageReceived(deviceId: String, rawJson: String)
        /** Called once when the desktop agent sends its version in connection_ack. Null = old agent. */
        fun onAgentVersionReceived(deviceId: String, agentVersion: String?, minCompanionVersion: String?) {}
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS) // Aggressive keep-alive to survive Doze
        .build()

    private val activeConnections = ConcurrentHashMap<String, WebSocket>()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>() // Tracks IP:port to prevent duplicate connections
    private val reconnectingDevices = ConcurrentHashMap.newKeySet<String>() // Prevents duplicate reconnect coroutines
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

                // Send our version info to desktop (one-shot, no polling)
                try {
                    val appVersion = context.packageManager
                        .getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                    val clientInfo = gson.toJson(mapOf(
                        "type" to "client_info",
                        "app" to "AutomationCompanion",
                        "version" to appVersion
                    ))
                    webSocket.send(clientInfo)
                    Log.d(TAG, "Sent client_info v$appVersion to ${device.name}")
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

                    // 2. Handle Control Messages
                    if (type == "connection_ack") {
                        Log.d(TAG, "Handshake received from ${device.name}: $text")
                        DebugLogger.info(
                            context, LogCategory.CROSS_DEVICE_SYNC,
                            "Connection Acknowledged",
                            "Handshake received from ${device.name}: $text",
                            TAG
                        )
                        // Check desktop version compatibility
                        // If version is null, the desktop is an old build that predates
                        // the version handshake — the listener will treat null as "outdated".
                        val agentVersion = jsonObject.get("version")?.asString
                        val minCompanion = jsonObject.get("min_companion_version")?.asString
                        this@NetworkingManager.listener?.onAgentVersionReceived(
                            device.id, agentVersion, minCompanion
                        )

                        // Parse agent/prelogin to verify isServiceOnly post-handshake
                        val agentField = jsonObject.get("agent")?.asString ?: ""
                        val preloginField = jsonObject.get("prelogin")?.asBoolean ?: false
                        val isServiceOnly = preloginField
                                || agentField.contains("prelogin", ignoreCase = true)
                        Log.d(TAG, "connection_ack: agent=$agentField, prelogin=$preloginField, isServiceOnly=$isServiceOnly")

                        // Update device with verified isServiceOnly state
                        scope.launch {
                            val existing = deviceRepository.getDeviceById(device.id)
                            if (existing != null && existing.isServiceOnly != isServiceOnly) {
                                deviceRepository.addOrUpdateDevice(
                                    existing.copy(isServiceOnly = isServiceOnly)
                                )
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

                    // 4. Handle Data Events
                    // Only try to parse as RawEvent if it looks like one, or let the listener handle it exclusively?
                    // For now, we still try to parse standard events for the eventPipeline.
                    if (type.startsWith("clipboard.") || type.contains("event")) {
                        val event = gson.fromJson(text, RawEvent::class.java)
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

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Closing: $reason")
                DebugLogger.warning(
                    context, LogCategory.CROSS_DEVICE_SYNC,
                    "WebSocket closing",
                    "Connection to ${device.name} closing. Code: $code, Reason: $reason",
                    TAG
                )
                webSocket.close(1000, null)
                activeConnections.remove(device.id)
                connectedEndpoints.remove("${device.ipAddress}:${device.port}")
                this@NetworkingManager.listener?.onDeviceDisconnected(device.id)
                // Don't reconnect on graceful close (user-initiated or deselect)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Connection failure: ${t.message}")
                DebugLogger.error(
                    context, LogCategory.CROSS_DEVICE_SYNC,
                    "WebSocket failure",
                    "Connection to ${device.name} failed: ${t.message}",
                    TAG
                )
                activeConnections.remove(device.id)
                connectedEndpoints.remove("${device.ipAddress}:${device.port}")
                this@NetworkingManager.listener?.onDeviceDisconnected(device.id)
                // Auto-reconnect if this device is still selected
                scheduleReconnect(device)
            }
        }

        client.newWebSocket(request, listener)
        return true
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

