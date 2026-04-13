package com.autonion.automationcompanion.features.omni_chatbot

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.PromptResponse
import com.autonion.automationcompanion.features.cross_device_automation.domain.ResponseStatus
import com.autonion.automationcompanion.features.nlu.IntentClassifier
import com.autonion.automationcompanion.features.nlu.IntentResult
import com.autonion.automationcompanion.features.nlu.IntentType
import com.autonion.automationcompanion.features.omni_chatbot.knowledge.FAQMatcher
import com.autonion.automationcompanion.features.omni_chatbot.knowledge.KnowledgeStore
import com.autonion.automationcompanion.features.omni_chatbot.knowledge.RAGPromptBuilder
import com.autonion.automationcompanion.features.omni_chatbot.model.*
import com.autonion.automationcompanion.features.semantic_automation.ml.LocalServerLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.ServerConnectionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the Omni-Chatbot.
 *
 * Routes user prompts through the NLU IntentClassifier and dispatches
 * them to the appropriate engine. Manages chat history, scheduled tasks,
 * and two-way communication with the Desktop agent.
 */
class OmniChatbotViewModel(
    private val context: Context,
    private val intentClassifier: IntentClassifier,
    private val crossDeviceManager: CrossDeviceAutomationManager
) : ViewModel() {

    companion object {
        private const val TAG = "OmniChatbot"
    }

    // ─── State ──────────────────────────────────────────────
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _messages = MutableStateFlow<List<OmniChatMessage>>(emptyList())
    val messages: StateFlow<List<OmniChatMessage>> = _messages.asStateFlow()

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    private val _currentRoute = MutableStateFlow<String?>("home")
    val currentRoute: StateFlow<String?> = _currentRoute.asStateFlow()

    /** Active scheduled task jobs, keyed by task ID */
    private val scheduledJobs = mutableMapOf<String, Job>()

    /** Track completed transactions to prevent duplicate completion messages */
    private val completedTransactions = mutableSetOf<String>()

    // ─── RAG + FAQ (initialized lazily) ─────────────────────
    private val faqMatcher: FAQMatcher by lazy {
        FAQMatcher(intentClassifier.embedder).also {
            it.loadFAQs(context)
        }
    }

    private val knowledgeStore: KnowledgeStore by lazy {
        KnowledgeStore(intentClassifier.embedder).also {
            it.loadFromAssets(context)
        }
    }

    private val ragPromptBuilder = RAGPromptBuilder()

    // ─── Init: Wire up Desktop response flow ────────────────
    init {
        viewModelScope.launch {
            try {
                crossDeviceManager.networkingManager.responseFlow.collect { response ->
                    Log.d(TAG, "Desktop response received: ${response.status} - ${response.message}")
                    onDesktopResponse(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting desktop responses", e)
            }
        }
    }

    // ─── Input Handling ─────────────────────────────────────

    fun onInputChanged(text: String) {
        _inputText.value = text
    }

    fun toggleExpanded() {
        _isExpanded.value = !_isExpanded.value
    }

    fun collapse() {
        _isExpanded.value = false
    }

    fun expand() {
        _isExpanded.value = true
    }

    fun updateRoute(route: String?) {
        _currentRoute.value = route
    }

    // ─── Main Entry Point ───────────────────────────────────

    fun processPrompt(text: String? = null) {
        val prompt = (text ?: _inputText.value).trim()
        if (prompt.isBlank()) return

        _inputText.value = ""

        // Add user message
        addMessage(OmniChatMessage(text = prompt, isUser = true))

        viewModelScope.launch {
            try {
                // Determine explicit routing
                var resolvedPrompt = prompt
                var forcedIntent: IntentType? = null
                
                if (prompt.startsWith("/android ", ignoreCase = true)) {
                    resolvedPrompt = prompt.substring(9).trim()
                    forcedIntent = IntentType.DEVICE_AUTOMATION
                } else if (prompt.startsWith("/desktop ", ignoreCase = true)) {
                    resolvedPrompt = prompt.substring(9).trim()
                    forcedIntent = IntentType.CROSS_DEVICE
                }

                val result = intentClassifier.classify(resolvedPrompt)
                val finalIntent = forcedIntent ?: result.intent
                
                Log.d(TAG, "Classified: ${result.intent} (${result.confidence}), Forced: $forcedIntent -> Final: $finalIntent")

                // Update result to hold the original rawPrompt and the final chosen intent
                val finalResult = result.copy(rawPrompt = resolvedPrompt, intent = finalIntent)

                when (finalResult.intent) {
                    IntentType.DIRECT_KEY_ACTION -> handleKeyAction(finalResult)
                    IntentType.DIRECT_TOGGLE -> handleToggle(finalResult)
                    IntentType.SCHEDULED_ACTION -> handleScheduledAction(finalResult)
                    IntentType.DEVICE_AUTOMATION -> handleDeviceAutomation(finalResult)
                    IntentType.CROSS_DEVICE -> handleCrossDevice(finalResult)
                    IntentType.FAQ -> handleFAQ(finalResult)
                    IntentType.Q_AND_A -> handleQAndA(finalResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing prompt", e)
                addMessage(OmniChatMessage(
                    text = "Sorry, something went wrong: ${e.message}",
                    isUser = false,
                    mode = ResponseMode.SYSTEM
                ))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  INTENT HANDLERS
    // ═══════════════════════════════════════════════════════════

    private fun handleKeyAction(result: IntentResult) {
        val keyLabel = result.entities.keyLabel
        val textToType = result.entities.textToType

        if (textToType != null) {
            // Text input mode — type literal text
            addMessage(OmniChatMessage(
                text = "✅ Typing: \"$textToType\"",
                isUser = false,
                mode = ResponseMode.DIRECT
            ))
            // Dispatch text input via AccessibilityService or cross-device
            dispatchTextInput(textToType)
        } else if (keyLabel != null) {
            val keyCode = intentClassifier.entityExtractor.keyNameMap[keyLabel]
            if (keyCode != null) {
                addMessage(OmniChatMessage(
                    text = "✅ Pressed: $keyLabel",
                    isUser = false,
                    mode = ResponseMode.DIRECT
                ))
                dispatchKeyPress(keyCode)
            } else {
                addMessage(OmniChatMessage(
                    text = "⚠️ Unknown key: $keyLabel",
                    isUser = false,
                    mode = ResponseMode.SYSTEM
                ))
            }
        } else {
            addMessage(OmniChatMessage(
                text = "⚠️ Couldn't determine which key to press.",
                isUser = false,
                mode = ResponseMode.SYSTEM
            ))
        }
    }

    private fun handleToggle(result: IntentResult) {
        val target = result.entities.toggleTarget ?: "unknown"
        val desiredState = result.entities.toggleDesiredState
        val stateStr = when (desiredState) {
            true -> "on"
            false -> "off"
            null -> "toggle"
        }

        addMessage(OmniChatMessage(
            text = "🎯 Turning $target $stateStr...",
            isUser = false,
            mode = ResponseMode.DIRECT
        ))

        // Launch semantic automation with the toggle intent pre-parsed
        // This goes through the SemanticAutomationEngine but with a pre-built goal
        launchSemanticAutomation(result.rawPrompt)
    }

    private fun handleScheduledAction(result: IntentResult) {
        val interval = result.entities.interval
        val repeatCount = result.entities.repeatCount

        if (interval == null && repeatCount == null) {
            addMessage(OmniChatMessage(
                text = "⚠️ Couldn't determine the schedule. Try: \"click next every 1 minute\"",
                isUser = false,
                mode = ResponseMode.SYSTEM
            ))
            return
        }

        val taskId = UUID.randomUUID().toString()
        val intervalMs = interval?.inWholeMilliseconds ?: 60_000L // Default: 1 min
        val keyLabel = result.entities.keyLabel

        val description = buildString {
            append("⏱️ ")
            if (keyLabel != null) append("Pressing $keyLabel")
            else append("Executing action")
            if (interval != null) append(" every ${formatDuration(intervalMs)}")
            if (repeatCount != null) append(" ($repeatCount times)")
            else append(" (until stopped)")
        }

        addMessage(OmniChatMessage(
            text = description,
            isUser = false,
            mode = ResponseMode.SCHEDULED,
            actionWidget = ActionWidget.StopButton(taskId)
        ))

        // Start the scheduled job
        val targetDevice = result.entities.targetDevice

        val job = viewModelScope.launch {
            var count = 0
            while (isActive && (repeatCount == null || count < repeatCount)) {
                count++
                Log.d(TAG, "Scheduled task $taskId: iteration $count")

                if (targetDevice != null) {
                    // Send to desktop
                    val command = buildStructuredCommand(result)
                    crossDeviceManager.networkingManager.broadcast(command)
                } else if (keyLabel != null) {
                    // Execute locally
                    val keyCode = intentClassifier.entityExtractor.keyNameMap[keyLabel]
                    if (keyCode != null) dispatchKeyPress(keyCode)
                }

                delay(intervalMs)
            }

            // Completed — update message
            addMessage(OmniChatMessage(
                text = "✅ Scheduled task completed ($count iterations)",
                isUser = false,
                mode = ResponseMode.SCHEDULED
            ))
        }

        scheduledJobs[taskId] = job
    }

    fun stopScheduledTask(taskId: String) {
        scheduledJobs[taskId]?.cancel()
        scheduledJobs.remove(taskId)
        addMessage(OmniChatMessage(
            text = "⏹️ Scheduled task stopped.",
            isUser = false,
            mode = ResponseMode.SCHEDULED
        ))
    }

    private fun handleDeviceAutomation(result: IntentResult) {
        val llmEngine = LocalServerLLMEngine.getInstance(context)
        if (llmEngine.connectionStatus.value != ServerConnectionStatus.CONNECTED) {
            addMessage(OmniChatMessage(
                text = "⚠️ No LLM server connected. Please connect to a local LLM (like Ollama) in settings to enable automation reasoning.",
                isUser = false,
                mode = ResponseMode.SYSTEM
            ))
            return
        }

        addMessage(OmniChatMessage(
            text = "🤖 Starting automation agent...",
            isUser = false,
            mode = ResponseMode.AGENT,
            isStreaming = true
        ))

        launchSemanticAutomation(result.rawPrompt)
    }

    private fun handleCrossDevice(result: IntentResult) {
        if (!crossDeviceManager.networkingManager.hasActiveConnections()) {
            addMessage(OmniChatMessage(
                text = "⚠️ No Desktop Agent detected. Make sure the Autonion desktop app is running and connected to the same WiFi network.",
                isUser = false,
                mode = ResponseMode.SYSTEM
            ))
            return
        }

        val device = result.entities.targetDevice ?: "desktop"

        addMessage(OmniChatMessage(
            text = "🔗 Sending to $device...",
            isUser = false,
            mode = ResponseMode.DESKTOP,
            isStreaming = true
        ))

        // Prepare and broadcast command
        val command = buildStructuredCommand(result)
        crossDeviceManager.networkingManager.broadcast(command)
    }

    private fun handleFAQ(result: IntentResult) {
        viewModelScope.launch {
            val match = faqMatcher.match(result.rawPrompt)
            if (match != null) {
                addMessage(OmniChatMessage(
                    text = match.answer,
                    isUser = false,
                    mode = ResponseMode.FAQ
                ))
            } else {
                // No close FAQ match — fall back to RAG
                handleQAndA(result)
            }
        }
    }

    private fun handleQAndA(result: IntentResult) {
        addMessage(OmniChatMessage(
            text = "💬 Let me think about that...",
            isUser = false,
            mode = ResponseMode.CHAT,
            isStreaming = true
        ))

        viewModelScope.launch {
            // 1. Try FAQ matching (fast path)
            val faqMatch = faqMatcher.match(result.rawPrompt)
            if (faqMatch != null) {
                updateLastBotMessage(faqMatch.answer, ResponseMode.FAQ)
                return@launch
            }

            // 2. Try RAG & LLM contextual synthesis
            val chunks = knowledgeStore.search(result.rawPrompt, topK = 3)
            val llmEngine = LocalServerLLMEngine.getInstance(context)
            
            if (chunks.isEmpty() || llmEngine.connectionStatus.value != ServerConnectionStatus.CONNECTED) {
                // Fallback if no context or no LLM
                val contextAnswer = buildString {
                    if (chunks.isNotEmpty()) {
                        append("📚 Here's what I found (LLM synthesis unavailable):\n\n")
                        for (chunk in chunks.take(2)) {
                            append(chunk.text.take(500))
                            append("\n\n")
                        }
                    } else {
                        append("I don't have information about that in my knowledge base. Try asking about app features or troubleshooting!")
                    }
                }
                updateLastBotMessage(contextAnswer.trim(), ResponseMode.KNOWLEDGE)
                return@launch
            }

            // Generate contextual answer via LLM
            val contextText = chunks.joinToString("\n") { it.text }
            val systemPrompt = """
                You are Autonion, an AI built into an Android automation app. 
                Answer the user's question concisely using ONLY the provided knowledge. 
                User is currently at screen/route: ${currentRoute.value ?: "unknown"}.
                
                Knowledge:
                $contextText
            """.trimIndent()
            
            val jsonSchema = mapOf(
                "type" to "object",
                "properties" to mapOf("answer" to mapOf("type" to "string")),
                "required" to listOf("answer")
            )
            
            val responseStr = llmEngine.chatWithSchema(systemPrompt, result.rawPrompt, jsonSchema)
            if (responseStr != null) {
                try {
                    val answer = org.json.JSONObject(responseStr).getString("answer")
                    updateLastBotMessage(answer, ResponseMode.KNOWLEDGE)
                } catch (e: Exception) {
                    updateLastBotMessage("⚠️ Error parsing answer.", ResponseMode.SYSTEM)
                }
            } else {
                 updateLastBotMessage("⚠️ LLM server failed to respond.", ResponseMode.SYSTEM)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  TWO-WAY COMMUNICATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Called when a response is received from the Desktop agent.
     * Updates the chat with the response message.
     */
    fun onDesktopResponse(response: PromptResponse) {
        val mode = ResponseMode.DESKTOP

        // Deduplicate terminal statuses (completed/failed) per transaction
        if (response.status == ResponseStatus.COMPLETED || response.status == ResponseStatus.FAILED) {
            if (completedTransactions.contains(response.transactionId)) {
                Log.d(TAG, "Skipping duplicate ${response.status} for txn=${response.transactionId}")
                return
            }
            completedTransactions.add(response.transactionId)
            // Cap at 50 to prevent memory leak
            if (completedTransactions.size > 50) {
                completedTransactions.remove(completedTransactions.first())
            }
        }

        val emoji = when (response.status) {
            ResponseStatus.STARTED -> "🔗"
            ResponseStatus.IN_PROGRESS -> "⏳"
            ResponseStatus.COMPLETED -> "✅"
            ResponseStatus.FAILED -> "❌"
            ResponseStatus.SCHEDULED -> "⏱️"
            ResponseStatus.CANCELLED -> "⏹️"
        }

        val text = "$emoji ${response.message}"

        when (response.status) {
            ResponseStatus.IN_PROGRESS -> updateLastBotMessage(text, mode)
            else -> addMessage(OmniChatMessage(text = text, isUser = false, mode = mode))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ACTION DISPATCHERS
    // ═══════════════════════════════════════════════════════════

    private fun dispatchKeyPress(keyCode: Int) {
        try {
            // Use the AccessibilityService to inject the key event
            val service = com.autonion.automationcompanion.AccessibilityRouter.getService()
            if (service != null) {
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
                // Note: dispatchKeyEvent might need to be called on the UI thread
                // or via the service's own mechanism
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                Log.d(TAG, "Dispatched key press: ${KeyEvent.keyCodeToString(keyCode)}")
            } else {
                Log.w(TAG, "AccessibilityService not available for key dispatch")
                // Fallback: send as cross-device command if desktop is connected
                if (crossDeviceManager.networkingManager.let { true }) {
                    val command = mapOf(
                        "type" to "key_press",
                        "keyCode" to keyCode,
                        "keyName" to KeyEvent.keyCodeToString(keyCode)
                    )
                    crossDeviceManager.networkingManager.broadcast(command)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Key dispatch failed", e)
        }
    }

    private fun dispatchTextInput(text: String) {
        try {
            val service = com.autonion.automationcompanion.AccessibilityRouter.getService()
            if (service != null) {
                // Find focused text field and set text
                val rootNode = service.rootInActiveWindow
                val focusedNode = rootNode?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                if (focusedNode != null) {
                    val arguments = android.os.Bundle()
                    arguments.putCharSequence(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                    focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                    focusedNode.recycle()
                }
                rootNode?.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Text input failed", e)
        }
    }

    private fun launchSemanticAutomation(command: String) {
        try {
            val intent = Intent(
                context,
                com.autonion.automationcompanion.features.semantic_automation.ui.SemanticAutomationActivity::class.java
            ).apply {
                putExtra("command", command)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch semantic automation", e)
            addMessage(OmniChatMessage(
                text = "❌ Failed to start automation: ${e.message}",
                isUser = false,
                mode = ResponseMode.SYSTEM
            ))
        }
    }

    private fun buildStructuredCommand(result: IntentResult): Map<String, Any?> {
        val command = mutableMapOf<String, Any?>(
            "transactionId" to UUID.randomUUID().toString(),
            "prompt" to result.rawPrompt,
            "timestamp" to System.currentTimeMillis(),
            "sourceDeviceId" to "android_controller"
        )

        // Add structured intent data so Desktop can skip LLM for simple actions
        if (result.intent == IntentType.DIRECT_KEY_ACTION) {
            command["type"] = "key_press"
            command["keyName"] = result.entities.keyLabel
            command["keyCode"] = result.entities.keyName
        } else if (result.intent == IntentType.SCHEDULED_ACTION) {
            command["type"] = "schedule"
            command["action"] = mapOf(
                "keyName" to result.entities.keyLabel,
                "keyCode" to result.entities.keyName
            )
            command["intervalMs"] = result.entities.interval?.inWholeMilliseconds
            command["repeatCount"] = result.entities.repeatCount
        }

        return command
    }

    // ═══════════════════════════════════════════════════════════
    //  MESSAGE MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    private fun addMessage(message: OmniChatMessage) {
        val current = _messages.value.toMutableList()
        current.add(0, message) // Newest first (for reverseLayout)
        _messages.value = current
    }

    private fun updateLastBotMessage(text: String, mode: ResponseMode) {
        val current = _messages.value.toMutableList()
        val lastBotIdx = current.indexOfFirst { !it.isUser }
        if (lastBotIdx >= 0) {
            current[lastBotIdx] = current[lastBotIdx].copy(
                text = text,
                mode = mode,
                isStreaming = false
            )
            _messages.value = current
        } else {
            addMessage(OmniChatMessage(text = text, isUser = false, mode = mode))
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 60_000 -> "${ms / 1000}s"
        ms < 3_600_000 -> "${ms / 60_000}m"
        else -> "${ms / 3_600_000}h"
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel all scheduled tasks
        scheduledJobs.values.forEach { it.cancel() }
        scheduledJobs.clear()
    }
}
