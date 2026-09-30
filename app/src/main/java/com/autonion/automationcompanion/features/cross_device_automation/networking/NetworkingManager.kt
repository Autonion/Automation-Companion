package com.autonion.automationcompanion.features.cross_device_automation.networking

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.cross_device_automation.data.DeviceAuthManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.*
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventReceiver
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** All connection lifecycle mutations run through one event queue. Socket callbacks carry
 * their attempt, so a late callback can never close or authenticate a replacement. */
class NetworkingManager(
    private val context: Context,
    private val deviceRepository: DeviceRepository,
    private val eventReceiver: EventReceiver,
    private val deviceAuthManager: DeviceAuthManager,
    private val client: WebSocket.Factory = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS).build(),
    private val retryDelayMs: Long = 250L
) {
    companion object { private const val TAG = "NetworkingManager" }

    interface NetworkingListener {
        fun onDeviceConnected(device: Device)
        fun onDeviceDisconnected(deviceId: String)
        fun onMessageReceived(deviceId: String, rawJson: String)
        fun onAgentVersionReceived(deviceId: String, agentVersion: String?, minCompanionVersion: String?) {}
        fun onPairingRequired(deviceId: String, deviceName: String) {}
        fun onPairingSuccess(deviceId: String) {}
        fun onPairingFailed(deviceId: String, error: String) {}
    }

    private class Attempt(val device: Device, val generation: Long, val endpoint: String) {
        var socket: WebSocket? = null
        var authenticated = false
        var agentId: String? = device.agentId
        var secret = ""
        var timeout: Job? = null
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val attempts = mutableMapOf<String, Attempt>()
    private val endpoints = mutableMapOf<String, Attempt>()
    private val retryJobs = mutableMapOf<String, Job>()
    private val retryCounts = mutableMapOf<String, Int>()
    private val blockedDevices = mutableSetOf<String>()
    private val activeConnections = ConcurrentHashMap<String, WebSocket>()
    private val gson = Gson()
    private var collectionJob: Job? = null
    @Volatile private var enabled = false
    @Volatile private var generation = 0L
    var listener: NetworkingListener? = null

    private val _responseFlow = MutableSharedFlow<PromptResponse>(extraBufferCapacity = 16)
    val responseFlow: SharedFlow<PromptResponse> = _responseFlow.asSharedFlow()

    init {
        scope.launch {
            for (event in events) {
                try { event() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e(TAG, "Connection event failed", e) }
            }
        }
    }

    private fun post(event: suspend () -> Unit) { events.trySend(event) }
    fun hasActiveConnections(): Boolean = enabled && activeConnections.isNotEmpty()
    fun connectedDeviceIds(): Set<String> = if (enabled) activeConnections.keys.toSet() else emptySet()

    @Synchronized fun start() {
        if (enabled) return
        enabled = true
        val epoch = ++generation
        collectionJob = scope.launch {
            for (saved in deviceAuthManager.rememberedDevices()) {
                if (!enabled || generation != epoch) return@launch
                if (deviceRepository.getAllDevices().first().none { it.id == saved.id || it.agentId == saved.agentId }) {
                    deviceRepository.addOrUpdateDevice(saved)
                    // Repository auto-selection is for newly discovered peers, not a saved deselection.
                    deviceRepository.mutateDevice(saved.id) { it.copy(isSelected = saved.isSelected) }
                }
            }
            deviceRepository.getAllDevices().collect { devices ->
                devices.forEach { deviceAuthManager.rememberDevice(it) }
                val selected = devices.filter { it.isSelected }
                val observedIds = selected.map { it.id }.toSet()
                post { if (enabled && generation == epoch) {
                    blockedDevices.retainAll(observedIds)
                    reconcile()
                } }
            }
        }
    }

    /** Fresh network/discovery evidence should not wait behind an old failure backoff. */
    fun retrySelectedConnections(deviceId: String? = null) = post {
        if (!enabled) return@post
        for (device in deviceRepository.getSelectedDevices().first()) {
            if (deviceId != null && device.id != deviceId) continue
            val attempt = attempts[device.id]
            if (device.id in blockedDevices || attempt?.authenticated == true ||
                device.connectionState == ConnectionState.PAIRING) continue
            // Repeated NSD results for the same endpoint must not restart an in-flight handshake.
            if (deviceId != null && attempt != null && attempt.endpoint == endpoint(device)) continue
            retryJobs.remove(device.id)?.cancel()
            retryCounts.remove(device.id)
            attempt?.let { finish(it, false, "Network or discovery refreshed") }
            connect(device)
        }
    }

    @Synchronized fun stop() {
        enabled = false
        val epoch = ++generation
        collectionJob?.cancel()
        collectionJob = null
        // Immediately prevent callers from sending new commands during shutdown.
        activeConnections.clear()
        post {
            retryJobs.values.forEach { it.cancel() }; retryJobs.clear()
            retryCounts.clear(); blockedDevices.clear()
            attempts.values.filter { it.generation < epoch }.toList().forEach {
                finish(it, retry = false, reason = "Sync stopped")
            }
        }
    }

    private suspend fun reconcile() {
        val selected = deviceRepository.getSelectedDevices().first().associateBy { it.id }
        blockedDevices.retainAll(selected.keys)
        for ((id, attempt) in attempts.toMap()) {
            val device = selected[id]
            if (device == null || endpoint(device) != attempt.endpoint || attempt.generation != generation) {
                retryJobs.remove(id)?.cancel()
                finish(attempt, retry = false, reason = "Selection or address changed")
            }
        }
        for (id in retryJobs.keys.toList()) if (id !in selected) {
            retryJobs.remove(id)?.cancel(); retryCounts.remove(id)
        }
        for (device in selected.values) {
            if (device.id !in attempts && device.id !in retryJobs && device.id !in blockedDevices) connect(device)
        }
    }

    private fun endpoint(device: Device): String = "${device.ipAddress}:${device.port}"
    private fun current(attempt: Attempt): Boolean = enabled && attempt.generation == generation &&
        attempts[attempt.device.id] === attempt

    private suspend fun wanted(attempt: Attempt): Boolean = current(attempt) &&
        deviceRepository.getDeviceById(attempt.device.id)?.let {
            it.isSelected && endpoint(it) == attempt.endpoint
        } == true

    private suspend fun setState(id: String, state: ConnectionState) {
        deviceRepository.mutateDevice(id) { it.copy(connectionState = state) }
    }

    private suspend fun connect(device: Device) {
        if (!enabled || !device.isSelected || device.id in attempts || endpoint(device) in endpoints) return
        val request = try {
            require(device.ipAddress.isNotBlank() && device.port in 1..65535)
            Request.Builder().url(HttpUrl.Builder().scheme("http").host(device.ipAddress)
                .port(device.port).addPathSegment("automation").build()).build()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid endpoint for ${device.name}: ${e.message}")
            scheduleReconnect(device.id)
            return
        }
        val attempt = Attempt(device, generation, endpoint(device))
        attempt.secret = deviceAuthManager.secretForAgent(attempt.agentId)
        attempts[device.id] = attempt; endpoints[attempt.endpoint] = attempt
        setState(device.id, ConnectionState.CONNECTING)
        try {
            attempt.socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = post {
                    if (!wanted(attempt)) { finish(attempt, false, "Obsolete connection"); return@post }
                    setState(device.id, ConnectionState.AUTHENTICATING)
                    val version = runCatching { context.packageManager
                        .getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown"
                    val sent = webSocket.send(gson.toJson(mapOf(
                        "type" to "client_info", "app" to "AutomationCompanion", "version" to version,
                        "deviceId" to deviceAuthManager.deviceId, "deviceName" to deviceAuthManager.deviceName,
                        "deviceSecret" to attempt.secret
                    )))
                    if (!sent) finish(attempt, true, "Identity send failed")
                }
                override fun onMessage(webSocket: WebSocket, text: String) = post {
                    if (wanted(attempt)) handleMessage(attempt, text)
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // An empty peer close frame is reported as 1005, which cannot be sent on the wire.
                    runCatching { webSocket.close(if (code == 1005) 1000 else code, null) }
                    post { closed(attempt, code, reason) }
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post { closed(attempt, code, reason) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post {
                    closed(attempt, 1006, t.message ?: "Connection failed")
                }
            })
            timeout(attempt, 20_000)
        } catch (e: Exception) { finish(attempt, true, e.message ?: "Connection setup failed") }
    }

    private fun timeout(attempt: Attempt, millis: Long) {
        attempt.timeout?.cancel()
        attempt.timeout = scope.launch {
            delay(millis)
            post { if (current(attempt)) finish(attempt, true, "Authentication timed out") }
        }
    }

    private suspend fun closed(attempt: Attempt, code: Int, reason: String) {
        if (attempts[attempt.device.id] !== attempt) return
        if (code == 4001) revoke(attempt)
        else {
            if (code == 4003) blockedDevices.add(attempt.device.id)
            finish(attempt, retry = code != 4003, reason = reason)
        }
    }

    private suspend fun finish(attempt: Attempt, retry: Boolean, reason: String) {
        val id = attempt.device.id
        if (attempts[id] !== attempt) return
        attempts.remove(id)
        if (endpoints[attempt.endpoint] === attempt) endpoints.remove(attempt.endpoint)
        attempt.timeout?.cancel()
        attempt.socket?.let { activeConnections.remove(id, it) }
        attempt.socket?.cancel()
        setState(id, ConnectionState.DISCONNECTED)
        if (attempt.authenticated) listener?.onDeviceDisconnected(id)
        Log.d(TAG, "Disconnected ${attempt.device.name}: $reason")
        if (retry) scheduleReconnect(id)
    }

    private suspend fun scheduleReconnect(id: String) {
        if (!enabled || id in retryJobs || id in blockedDevices ||
            deviceRepository.getDeviceById(id)?.isSelected != true) return
        val count = retryCounts[id] ?: 0
        retryCounts[id] = (count + 1).coerceAtMost(8)
        val wait = (retryDelayMs * (1L shl count.coerceAtMost(7))).coerceAtMost(30_000L)
        val epoch = generation
        setState(id, ConnectionState.RECONNECTING)
        retryJobs[id] = scope.launch {
            delay(wait)
            post {
                if (epoch != generation || !enabled) return@post
                retryJobs.remove(id)
                val latest = deviceRepository.getDeviceById(id)
                if (latest?.isSelected == true && id !in blockedDevices) connect(latest)
            }
        }
    }

    fun submitPairingPin(deviceId: String, pin: String) = post {
        val attempt = attempts[deviceId] ?: return@post
        if (!wanted(attempt)) return@post
        attempt.secret = deviceAuthManager.secretForAgent(attempt.agentId)
        attempt.socket?.send(gson.toJson(mapOf(
            "type" to "pairing_submit", "pin" to pin.trim(), "deviceId" to deviceAuthManager.deviceId,
            "deviceName" to deviceAuthManager.deviceName, "deviceSecret" to attempt.secret
        )))
    }

    fun disconnectDevice(deviceId: String) = post {
        blockedDevices.add(deviceId)
        retryJobs.remove(deviceId)?.cancel()
        attempts[deviceId]?.let { finish(it, false, "Device disconnected by user") }
    }

    private suspend fun revoke(attempt: Attempt) {
        val id = attempt.device.id
        blockedDevices.add(id)
        attempt.agentId?.let { deviceAuthManager.unpairAgent(it) }
        deviceRepository.mutateDevice(id) {
            it.copy(isPaired = false, isPairingRequired = true, isSelected = false)
        }
        finish(attempt, false, "Pairing revoked")
        listener?.onPairingFailed(id, "Pairing revoked by desktop host.")
    }

    fun sendCommand(deviceId: String, command: Any): Boolean {
        if (!enabled) return false
        val socket = activeConnections[deviceId] ?: return false
        val sent = socket.send(gson.toJson(command))
        if (!sent) post { attempts[deviceId]?.takeIf { it.socket === socket }?.let {
            finish(it, true, "Command send failed")
        } }
        return sent
    }

    fun broadcast(event: Any) { connectedDeviceIds().forEach { sendCommand(it, event) } }

    private suspend fun handleMessage(attempt: Attempt, text: String) {
        val device = attempt.device
        try {
            val jsonObject = gson.fromJson(text, com.google.gson.JsonObject::class.java)
            val type = jsonObject.get("type")?.asString ?: return
            if (type == "auth_result" || type == "connection_ack") {
                val status = jsonObject.get("status")?.asString ?: return
                val agentId = jsonObject.get("agent_id")?.asString ?: attempt.agentId
                val isServiceOnly = jsonObject.get("prelogin")?.asBoolean == true ||
                    jsonObject.get("agent")?.asString?.contains("prelogin", ignoreCase = true) == true
                when (status) {
                    "authenticated", "paired_success" -> {
                        if (attempt.authenticated) return
                        attempt.agentId = agentId
                        if (agentId != null) deviceAuthManager.markAgentPaired(agentId, attempt.secret)
                        attempt.authenticated = true
                        attempt.timeout?.cancel()
                        retryCounts.remove(device.id)
                        activeConnections[device.id] = attempt.socket!!
                        deviceRepository.mutateDevice(device.id) { it.copy(
                            connectionState = ConnectionState.CONNECTED, isPaired = true,
                            isPairingRequired = false, agentId = agentId, isServiceOnly = isServiceOnly
                        ) }
                        listener?.onAgentVersionReceived(device.id, jsonObject.get("version")?.asString,
                            jsonObject.get("min_companion_version")?.asString)
                        listener?.onPairingSuccess(device.id)
                        deviceRepository.getDeviceById(device.id)?.let { listener?.onDeviceConnected(it) }
                    }
                    "connected" -> {
                        // Older helpers acknowledge transport without authenticating the phone.
                        blockedDevices.add(device.id)
                        finish(attempt, false, "Peer does not support authenticated connections")
                        listener?.onPairingFailed(device.id, "Update the Desktop Agent and unlock helper to connect securely.")
                    }
                    "pairing_required" -> {
                        attempt.agentId = agentId
                        deviceRepository.mutateDevice(device.id) { it.copy(
                            connectionState = ConnectionState.PAIRING, isPaired = false,
                            isPairingRequired = true, agentId = agentId
                        ) }
                        timeout(attempt, 130_000)
                        listener?.onPairingRequired(device.id, device.name)
                    }
                    "pairing_revoked" -> revoke(attempt)
                    "pairing_failed" -> listener?.onPairingFailed(device.id,
                        jsonObject.get("error")?.asString ?: "Invalid PIN")
                    "pairing_busy" -> {
                        listener?.onPairingFailed(device.id, "Another pairing is in progress on this desktop.")
                        finish(attempt, true, "Pairing busy")
                    }
                    "pairing_disabled", "pairing_expired", "pairing_rejected", "outdated_companion", "authentication_failed" -> {
                        blockedDevices.add(device.id)
                        listener?.onPairingFailed(device.id, jsonObject.get("message")?.asString
                            ?: jsonObject.get("error")?.asString ?: "Pairing was not accepted. Reconnect to try again.")
                        finish(attempt, false, status)
                    }
                }
                return
            }
            if (!attempt.authenticated) return
            listener?.onMessageReceived(device.id, text)
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

        } catch (e: Exception) { Log.e(TAG, "Failed to parse message from ${device.name}", e) }
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

}
