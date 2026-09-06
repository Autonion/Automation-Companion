package com.autonion.automationcompanion.features.semantic_automation.core

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A local WebSocket server running inside the Android app.
 *
 * The Semantic Bridge extension (Firefox Nightly) connects here and acts as a
 * passive relay:
 *
 *   1. **DOM Snapshots**: The extension captures the browser's interactive DOM
 *      elements and sends them here. The SemanticAutomationEngine consumes these
 *      snapshots alongside accessibility UI tree data to build richer prompts
 *      for the Local LLM (Ollama).
 *
 *   2. **Action Execution**: This server sends action commands (click, type,
 *      scroll, etc.) to the extension, which dispatches them to the content
 *      script for execution in the browser DOM.
 *
 *   3. **Automation Loop**: After each action, the extension automatically
 *      captures a fresh DOM snapshot and sends it back, enabling the
 *      LLM-driven automation loop:
 *
 *        DOM Snapshot → LLM Decision → Action Command → DOM Snapshot → ...
 *
 * The server listens on 0.0.0.0:4545 so both IPv4 and IPv6 localhost
 * connections from Firefox on Android are accepted.
 */
class ExtensionBridgeServer(port: Int = 54321) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "ExtensionBridge"
        private const val ACTION_TIMEOUT_MS = 15_000L

        @Volatile private var instance: ExtensionBridgeServer? = null
        @Volatile private var appContext: Context? = null

        fun getInstance(context: Context? = null): ExtensionBridgeServer {
            if (context != null) {
                appContext = context.applicationContext
            }
            return instance ?: synchronized(this) {
                instance ?: ExtensionBridgeServer().also {
                    instance = it
                    it.startServerSafely()
                }
            }
        }

        fun stopServer() {
            synchronized(this) {
                try {
                    instance?.stop(1000)
                } catch (_: Exception) { }
                instance = null
            }
        }
    }

    // ── Connection State ─────────────────────────────────────

    private var activeConnection: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isExtensionConnected = MutableStateFlow(false)
    val isExtensionConnected: StateFlow<Boolean> = _isExtensionConnected.asStateFlow()

    // ── DOM Snapshot State ───────────────────────────────────

    /**
     * The latest DOM snapshot received from the extension.
     * The SemanticAutomationAgent observes this flow to get browser context.
     *
     * Structure (JSON): {
     *   page: { url, title, viewport: { width, height, scroll_x, scroll_y } },
     *   summary: { note, interactive_count, text_sample },
     *   dom_nodes: [ { id, tag, text, interactive, bounds, android_ui, ... } ],
     *   interactive_elements: [ ... subset of dom_nodes ... ]
     * }
     */
    private val _latestDomSnapshot = MutableStateFlow<JSONObject?>(null)
    val latestDomSnapshot: StateFlow<JSONObject?> = _latestDomSnapshot.asStateFlow()

    // ── Pending Action Results ───────────────────────────────

    private val pendingActions = ConcurrentHashMap<String, CompletableDeferred<ActionResult>>()

    data class ActionResult(
        val success: Boolean,
        val message: String?,
        val error: String?,
        val pageUrl: String? = null,
        val pageTitle: String? = null
    )

    // A deferred for DOM snapshots requested on-demand
    @Volatile
    private var pendingDomRequest: CompletableDeferred<JSONObject?>? = null

    // ── Server Lifecycle ─────────────────────────────────────

    private fun startServerSafely() {
        try {
            connectionLostTimeout = 0
            start()
            Log.d(TAG, "ExtensionBridgeServer started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ExtensionBridgeServer (port may be in use)", e)
            try {
                instance = null
                val retryServer = ExtensionBridgeServer()
                retryServer.isReuseAddr = true
                retryServer.connectionLostTimeout = 0
                retryServer.start()
                instance = retryServer
                Log.d(TAG, "ExtensionBridgeServer started on retry (port reuse)")
            } catch (e2: Exception) {
                Log.e(TAG, "Retry also failed", e2)
            }
        }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val resourcePath = handshake.resourceDescriptor ?: "/"
        Log.d(TAG, "Client connected from ${conn.remoteSocketAddress}, path=$resourcePath")
        activeConnection = conn
        _isExtensionConnected.value = true

        // Acknowledge connection
        val ack = JSONObject().apply {
            put("type", "connection_ack")
            put("agent", "android_companion")
            put("capabilities", JSONArray().apply {
                put("dom_snapshot")
                put("execute_action")
                put("request_dom_snapshot")
            })
        }
        conn.send(ack.toString())
        Log.d(TAG, "Sent connection_ack to Semantic Bridge extension")

        // Log to DebugLogger so the user sees it in the app UI
        appContext?.let { ctx ->
            DebugLogger.success(
                ctx, LogCategory.SEMANTIC_AUTOMATION,
                "Browser Extension Connected",
                "Semantic Bridge extension connected from ${conn.remoteSocketAddress}",
                TAG
            )
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "Client disconnected: code=$code reason=$reason remote=$remote")
        if (activeConnection == conn) {
            activeConnection = null
            _isExtensionConnected.value = false
            _latestDomSnapshot.value = null
            Log.d(TAG, "Active connection cleared")

            appContext?.let { ctx ->
                DebugLogger.warning(
                    ctx, LogCategory.SEMANTIC_AUTOMATION,
                    "Browser Extension Disconnected",
                    "Extension disconnected (code=$code, reason=$reason)",
                    TAG
                )
            }
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "Received: ${message.take(300)}")
        try {
            val json = JSONObject(message)
            val type = json.optString("type")

            when (type) {
                // ── DOM Snapshot (from extension) ─────────────
                "dom_snapshot" -> {
                    handleDomSnapshot(json)
                }

                // ── Action Result (from extension) ───────────
                "action_result" -> {
                    handleActionResult(json)
                }

                // ── Bridge Hello (extension identifies itself) ─
                "bridge_hello" -> {
                    val flavor = json.optString("extension_flavor", "unknown")
                    Log.d(TAG, "Extension identified: flavor=$flavor")
                }

                // ── Legacy: execution_status (old chatbot flow) ─
                "execution_status" -> {
                    val status = json.optString("status")
                    val txId = json.optString("transaction_id")
                    Log.d(TAG, "Legacy execution_status: $status for tx=$txId")
                }

                // ── Ping ─────────────────────────────────────
                "ping" -> {
                    val pong = JSONObject().apply { put("type", "pong") }
                    conn.send(pong.toString())
                }

                // ── Content script log ───────────────────────
                "content_log" -> {
                    Log.d(TAG, "Extension log: ${json.optString("message")}")
                }

                else -> {
                    Log.d(TAG, "Unhandled message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from extension", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocketServer Error: ${ex.message}", ex)
    }

    override fun onStart() {
        Log.d(TAG, "Server successfully started and listening on port $port")
    }

    // ══════════════════════════════════════════════════════════
    // DOM Snapshot Handling
    // ══════════════════════════════════════════════════════════

    private fun handleDomSnapshot(json: JSONObject) {
        val nodeCount = json.optJSONArray("dom_nodes")?.length() ?: 0
        val interactiveCount = json.optJSONArray("interactive_elements")?.length() ?: 0
        val reason = json.optString("reason", "unknown")
        val pageUrl = json.optJSONObject("page")?.optString("url", "") ?: ""
        val pageTitle = json.optJSONObject("page")?.optString("title", "") ?: ""
        Log.d(TAG, "DOM snapshot received: $nodeCount nodes, reason=$reason, url=${pageUrl.take(80)}")

        _latestDomSnapshot.value = json

        // Log to DebugLogger so user sees DOM snapshots arriving
        appContext?.let { ctx ->
            DebugLogger.info(
                ctx, LogCategory.SEMANTIC_AUTOMATION,
                "DOM Snapshot Received",
                "$nodeCount nodes ($interactiveCount interactive), reason=$reason\n" +
                    "URL: ${pageUrl.take(80)}\nTitle: ${pageTitle.take(60)}",
                TAG
            )
        }

        // If someone was waiting for an on-demand snapshot, complete it
        pendingDomRequest?.let { deferred ->
            if (!deferred.isCompleted) {
                deferred.complete(json)
            }
            pendingDomRequest = null
        }
    }

    /**
     * Request a fresh DOM snapshot from the extension.
     * Returns the snapshot JSON, or null if the extension is not connected or times out.
     */
    suspend fun requestDomSnapshot(timeoutMs: Long = 10_000L): JSONObject? {
        val conn = activeConnection
        if (conn == null || !conn.isOpen) {
            Log.w(TAG, "Cannot request DOM snapshot: extension not connected")
            return null
        }

        val deferred = CompletableDeferred<JSONObject?>()
        pendingDomRequest = deferred

        val requestId = UUID.randomUUID().toString().take(8)
        val msg = JSONObject().apply {
            put("type", "request_dom_snapshot")
            put("request_id", requestId)
        }

        try {
            conn.send(msg.toString())
            Log.d(TAG, "Requested DOM snapshot (id=$requestId)")
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            Log.e(TAG, "requestDomSnapshot failed", e)
            pendingDomRequest = null
            return null
        }
    }

    // ══════════════════════════════════════════════════════════
    // Action Execution
    // ══════════════════════════════════════════════════════════

    private fun handleActionResult(json: JSONObject) {
        val actionId = json.optString("action_id", "")
        val success = json.optBoolean("success", false)
        val message = json.optString("message", null)
        val error = json.optString("error", null)
        val pageUrl = json.optString("page_url", null)
        val pageTitle = json.optString("page_title", null)

        Log.d(TAG, "Action result: id=$actionId success=$success msg=${message ?: error}")

        val result = ActionResult(
            success = success,
            message = message,
            error = error,
            pageUrl = pageUrl,
            pageTitle = pageTitle
        )

        val deferred = pendingActions.remove(actionId)
        if (deferred != null) {
            deferred.complete(result)
        } else {
            Log.w(TAG, "Received action_result for unknown actionId=$actionId")
        }
    }

    /**
     * Send an action to the browser extension for execution.
     *
     * @param actionName The action type: click_element, type_into, scroll_down, scroll_up,
     *                   press_key, navigate, open_url, go_back, refresh, wait, etc.
     * @param params Action-specific parameters (element_id, text, selector, direction, url, etc.)
     * @return ActionResult indicating success/failure, or null if not connected / timed out.
     *
     * After a successful action, the extension will automatically send a fresh DOM snapshot
     * which will update [latestDomSnapshot].
     */
    suspend fun executeAction(actionName: String, params: Map<String, Any?> = emptyMap()): ActionResult? {
        val conn = activeConnection
        if (conn == null || !conn.isOpen) {
            Log.w(TAG, "Cannot execute action: extension not connected")
            return ActionResult(success = false, message = null, error = "Extension not connected")
        }

        val actionId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ActionResult>()
        pendingActions[actionId] = deferred

        val actionObj = JSONObject().apply {
            put("action", actionName)
            put("params", JSONObject(params.filterValues { it != null }))
        }
        val msg = JSONObject().apply {
            put("type", "execute_action")
            put("action_id", actionId)
            put("action", actionObj)
        }

        try {
            conn.send(msg.toString())
            Log.d(TAG, "Sent execute_action: $actionName (id=${actionId.take(8)})")
            return withTimeoutOrNull(ACTION_TIMEOUT_MS) { deferred.await() }
                ?: run {
                    pendingActions.remove(actionId)
                    Log.w(TAG, "Action timed out: $actionName (id=${actionId.take(8)})")
                    ActionResult(success = false, message = null, error = "Action timed out")
                }
        } catch (e: Exception) {
            pendingActions.remove(actionId)
            Log.e(TAG, "executeAction failed", e)
            return ActionResult(success = false, message = null, error = "Exception: ${e.message}")
        }
    }

    // ── Convenience Action Methods ───────────────────────────

    /**
     * Click an element by its node ID from the DOM snapshot.
     */
    suspend fun clickElement(elementId: String): ActionResult? {
        return executeAction("click_element", mapOf("element_id" to elementId))
    }

    /**
     * Type text into an element by its node ID.
     */
    suspend fun typeInto(elementId: String, text: String, pressEnter: Boolean = false): ActionResult? {
        return executeAction("type_into", mapOf(
            "element_id" to elementId,
            "text" to text,
            "press_enter" to pressEnter
        ))
    }

    /**
     * Scroll the page down.
     */
    suspend fun scrollDown(): ActionResult? {
        return executeAction("scroll_down", mapOf("direction" to "down"))
    }

    /**
     * Scroll the page up.
     */
    suspend fun scrollUp(): ActionResult? {
        return executeAction("scroll_up", mapOf("direction" to "up"))
    }

    /**
     * Navigate to a URL.
     */
    suspend fun navigateTo(url: String): ActionResult? {
        return executeAction("navigate", mapOf("url" to url))
    }

    /**
     * Press a key (Enter, Tab, Escape, etc.)
     */
    suspend fun pressKey(key: String, elementId: String? = null): ActionResult? {
        return executeAction("press_key", mapOf("key" to key, "element_id" to elementId))
    }

    // ══════════════════════════════════════════════════════════
    // Legacy: execute_prompt (for backward compat, if needed)
    // ══════════════════════════════════════════════════════════

    private val pendingExecutions = mutableMapOf<String, CompletableDeferred<Boolean>>()

    /**
     * Legacy: Send a prompt to the extension for chatbot-based execution.
     * This is for the old Autonion-Extension flow; the Semantic Bridge extension
     * does NOT support this and will return an error.
     */
    suspend fun executePromptInBrowser(prompt: String): Boolean {
        val conn = activeConnection
        if (conn == null || !conn.isOpen) {
            Log.w(TAG, "Cannot execute prompt, no extension connected")
            return false
        }

        val txId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        pendingExecutions[txId] = deferred

        val msg = JSONObject().apply {
            put("type", "execute_prompt")
            val payload = JSONObject().apply {
                put("prompt", prompt)
                put("transaction_id", txId)
            }
            put("payload", payload)
        }

        try {
            conn.send(msg.toString())
            Log.d(TAG, "Sent execute_prompt to extension: $prompt (tx=${txId.take(8)})")
            return deferred.await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            pendingExecutions.remove(txId)
            return false
        }
    }

    /**
     * True if the extension is currently attached and the socket is open.
     */
    fun isConnected(): Boolean {
        val connected = activeConnection?.isOpen == true
        Log.d(TAG, "isConnected check: $connected (activeConnection=${activeConnection != null})")
        return connected
    }
}
