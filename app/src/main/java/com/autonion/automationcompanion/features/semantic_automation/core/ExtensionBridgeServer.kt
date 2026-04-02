package com.autonion.automationcompanion.features.semantic_automation.core

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * A local WebSocket server running inside the Android app.
 * 
 * Two connection paths are supported:
 *   1. Background script: The extension's background.js connects directly (desktop-style).
 *   2. Content script bridge: android-bridge.js content script connects on every page load.
 *      This is the reliable fallback for Firefox Nightly on Android where background
 *      scripts may be suspended or fail to establish WebSocket connections.
 * 
 * The server listens on 0.0.0.0:4545 (all interfaces) so both IPv4 (127.0.0.1)
 * and IPv6 (::1) localhost connections from the browser are accepted.
 */
class ExtensionBridgeServer(port: Int = 4545) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    companion object {
        private const val TAG = "ExtensionBridge"
        @Volatile private var instance: ExtensionBridgeServer? = null

        fun getInstance(): ExtensionBridgeServer {
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

    private var activeConnection: WebSocket? = null
    private val pendingExecutions = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val scope = CoroutineScope(Dispatchers.IO)

    private fun startServerSafely() {
        try {
            connectionLostTimeout = 0  // Disable built-in timeout (we handle our own ping)
            start()
            Log.d(TAG, "ExtensionBridgeServer started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ExtensionBridgeServer (port may be in use)", e)
            // If port is in use from a previous instance, kill it and retry
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
        
        // Acknowledge connection — works for both background script and content script bridge
        val ack = JSONObject().apply {
            put("type", "connection_ack")
            put("agent", "android_companion")
        }
        conn.send(ack.toString())
        Log.d(TAG, "Sent connection_ack to client")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.d(TAG, "Client disconnected: code=$code reason=$reason remote=$remote")
        if (activeConnection == conn) {
            activeConnection = null
            Log.d(TAG, "Active connection cleared")
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "Received message: ${message.take(200)}")
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            
            when (type) {
                "execution_status" -> {
                    val status = json.optString("status")
                    val txId = json.optString("transaction_id")
                    Log.d(TAG, "Execution status: $status for tx=$txId")
                    
                    if (status == "completed" || status == "error") {
                        val deferred = pendingExecutions.remove(txId)
                        val success = status == "completed"
                        deferred?.complete(success)
                        if (!success) {
                            Log.e(TAG, "Extension execution failed: ${json.optString("message")}")
                        }
                    }
                }
                "bridge_hello" -> {
                    // Content script bridge connected — this is the reliable Android path
                    val url = json.optString("url")
                    Log.d(TAG, "Content script bridge connected from page: $url")
                }
                "ping" -> {
                    val pong = JSONObject().apply { put("type", "pong") }
                    conn.send(pong.toString())
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

    /**
     * Send a prompt to the extension for execution.
     * Returns true if successfully completed according to the extension, false otherwise.
     */
    suspend fun executePromptInBrowser(prompt: String): Boolean {
        val conn = activeConnection
        if (conn == null || !conn.isOpen) {
            Log.w(TAG, "Cannot execute prompt, no extension connected")
            return false
        }

        val txId = java.util.UUID.randomUUID().toString()
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
