package com.autonion.automationcompanion.features.cross_device_automation.networking

import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRepository
import com.autonion.automationcompanion.features.cross_device_automation.domain.RawEvent
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventReceiver
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val deviceRepository: DeviceRepository,
    private val eventReceiver: EventReceiver
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep-alive for background stability
        .build()

    private val activeConnections = ConcurrentHashMap<String, WebSocket>()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    private var collectionJob: kotlinx.coroutines.Job? = null

    fun start() {
        if (collectionJob?.isActive == true) return

        collectionJob = scope.launch {
            deviceRepository.getAllDevices().collectLatest { devices ->
                devices.forEach { device ->
                    if (!activeConnections.containsKey(device.id)) {
                        connectToDevice(device)
                    }
                }
            }
        }
    }

    interface NetworkingListener {
        fun onDeviceConnected(device: Device)
        fun onDeviceDisconnected(deviceId: String)
        fun onMessageReceived(deviceId: String, message: String)
    }

    private var listener: NetworkingListener? = null

    fun setListener(listener: NetworkingListener) {
        this.listener = listener
    }

    private fun connectToDevice(device: Device) {
        val request = Request.Builder()
            .url("ws://${device.ipAddress}:${device.port}/automation") // Assuming path
            .build()

        Log.d("NetworkingManager", "Connecting to ${device.name} at ${device.ipAddress}")

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("NetworkingManager", "Connected to ${device.name}")
                activeConnections[device.id] = webSocket
                this@NetworkingManager.listener?.onDeviceConnected(device)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Forward raw message to listener (e.g. for Rule Triggers)
                this@NetworkingManager.listener?.onMessageReceived(device.id, text)

                try {
                    // 1. Parse as generic JsonObject first to check message type
                    val jsonObject = gson.fromJson(text, com.google.gson.JsonObject::class.java)
                    val type = jsonObject.get("type")?.asString

                    if (type == null) {
                        Log.w("NetworkingManager", "Received message without type: $text")
                        return
                    }

                    // 2. Handle Control Messages
                    if (type == "connection_ack") {
                        Log.d("NetworkingManager", "Handshake received from ${device.name}: $text")
                        return // Don't try to parse as RawEvent
                    }

                    // 3. Handle Data Events
                    // Only try to parse as RawEvent if it looks like one, or let the listener handle it exclusively?
                    // For now, we still try to parse standard events for the eventPipeline.
                    if (type.startsWith("clipboard.") || type.contains("event")) {
                        val event = gson.fromJson(text, RawEvent::class.java)
                        scope.launch {
                            eventReceiver.onEventReceived(event)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NetworkingManager", "Failed to parse message: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("NetworkingManager", "Closing: $reason")
                webSocket.close(1000, null)
                activeConnections.remove(device.id)
                this@NetworkingManager.listener?.onDeviceDisconnected(device.id)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("NetworkingManager", "Connection failure: ${t.message}")
                activeConnections.remove(device.id)
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

        // Do NOT shutdown executor as client is reused.
        client.dispatcher.cancelAll()
    }

}

