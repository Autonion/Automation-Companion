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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
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
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep-alive for background stability
        .build()

    private val activeConnections = ConcurrentHashMap<String, WebSocket>()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>() // Tracks IP:port to prevent duplicate connections
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
            deviceRepository.getAllDevices().collectLatest { devices ->
                devices.forEach { device ->
                    val endpoint = "${device.ipAddress}:${device.port}"
                    if (!activeConnections.containsKey(device.id) && !connectedEndpoints.contains(endpoint)) {
                        connectToDevice(device)
                    }
                }
            }
        }
    }



    private fun connectToDevice(device: Device) {
        val request = Request.Builder()
            .url("ws://${device.ipAddress}:${device.port}/automation") // Assuming path
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
                activeConnections[device.id] = webSocket
                connectedEndpoints.add("${device.ipAddress}:${device.port}")
                this@NetworkingManager.listener?.onDeviceConnected(device)
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
                        return // Don't try to parse as RawEvent
                    }

                    // 3. Handle Prompt Response (two-way communication from Desktop)
                    if (type == "prompt_response") {
                        val transactionId = jsonObject.get("transactionId")?.asString ?: ""
                        val status = jsonObject.get("status")?.asString ?: "unknown"
                        val message = jsonObject.get("message")?.asString ?: ""

                        val responseStatus = try {
                            ResponseStatus.valueOf(status.uppercase())
                        } catch (_: Exception) {
                            ResponseStatus.IN_PROGRESS
                        }

                        val response = PromptResponse(
                            transactionId = transactionId,
                            status = responseStatus,
                            message = message
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
            }
        }

        client.newWebSocket(request, listener)
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

}

