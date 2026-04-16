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
import com.autonion.automationcompanion.features.omni_chatbot.knowledge.FAQRepository
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
import kotlinx.coroutines.flow.transformWhile
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

    // ─── LLM Engine (exposed for in-chat settings) ──────────
    private val llmEngine = LocalServerLLMEngine.getInstance(context)
    // For reading/writing inference mode prefs (uses SharedPreferences — instance doesn't matter)
    private val inferencePrefsEngine = com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine(context)

    val llmConnectionStatus = llmEngine.connectionStatus
    val llmServerUrl = llmEngine.serverUrl
    val llmSelectedModel = llmEngine.selectedModelName
    val llmAvailableModels = llmEngine.availableModels

    // Inference mode: LOCAL_SLM vs SERVER_LLM
    private val _inferenceMode = MutableStateFlow(inferencePrefsEngine.inferenceMode)
    val inferenceMode: StateFlow<com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode> = _inferenceMode.asStateFlow()

    private val _showSettings = MutableStateFlow(false)
    val showSettings: StateFlow<Boolean> = _showSettings.asStateFlow()

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

    // ─── RAG + FAQ (initialized synchronously, loaded asynchronously) ─────
    private val faqRepository = FAQRepository()
    private val knowledgeStore = KnowledgeStore(intentClassifier.embedder)

    private val ragPromptBuilder = RAGPromptBuilder()

    // ─── FAQ Browser State ──────────────────────────────────
    private val _showFAQBrowser = MutableStateFlow(false)
    val showFAQBrowser: StateFlow<Boolean> = _showFAQBrowser.asStateFlow()

    private val _faqList = MutableStateFlow<List<FAQRepository.FAQ>>(emptyList())
    val faqList: StateFlow<List<FAQRepository.FAQ>> = _faqList.asStateFlow()

    // ─── Init: Wire up Desktop response flow ────────────────
    init {
        viewModelScope.launch {
            // Load large embeddings in background immediately without blocking UI
            faqRepository.loadFAQs(context)
            _faqList.value = faqRepository.getAllFAQs()
            
            knowledgeStore.loadFromAssets(context)
        }

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
        _showSettings.value = false
    }

    fun expand() {
        _isExpanded.value = true
        autoConnectIfNeeded()
    }

    fun updateRoute(route: String?) {
        _currentRoute.value = route
    }

    fun toggleSettings() {
        _showSettings.value = !_showSettings.value
        if (_showSettings.value) _showFAQBrowser.value = false
    }

    fun toggleFAQBrowser() {
        _showFAQBrowser.value = !_showFAQBrowser.value
        if (_showFAQBrowser.value) _showSettings.value = false
    }

    fun onFAQSelected(faq: FAQRepository.FAQ) {
        _showFAQBrowser.value = false
        // Echo user selection
        addMessage(OmniChatMessage(
            text = faq.question,
            isUser = true,
            mode = ResponseMode.DIRECT
        ))
        // Immediate static response from FAQ repository
        addMessage(OmniChatMessage(
            text = faq.answer,
            isUser = false,
            mode = ResponseMode.FAQ
        ))
    }

    // ─── LLM Connection Management ──────────────────────────

    /**
     * Connect to an Ollama server by IP address or full URL.
     * Called from the in-chat settings panel.
     */
    fun connectToServer(ipOrUrl: String) {
        val url = if (ipOrUrl.startsWith("http")) ipOrUrl
                  else "http://$ipOrUrl:11434"
        llmEngine.setServerUrl(url)
        viewModelScope.launch {
            llmEngine.initialize()
        }
    }

    /**
     * Select which Ollama model to use for generation.
     */
    fun selectModel(modelName: String) {
        llmEngine.setModel(modelName)
    }

    /**
     * Switch inference mode between on-device SLM and server LLM.
     */
    fun setInferenceMode(mode: com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode) {
        inferencePrefsEngine.inferenceMode = mode
        _inferenceMode.value = mode
    }

    /**
     * Auto-reconnect using saved URL when chat is opened.
     * Only triggers if there's a saved URL but the engine is disconnected.
     */
    private fun autoConnectIfNeeded() {
        if (llmEngine.connectionStatus.value == ServerConnectionStatus.DISCONNECTED
            && llmEngine.serverUrl.value.isNotBlank()
        ) {
            Log.d(TAG, "Auto-reconnecting to saved LLM server: ${llmEngine.serverUrl.value}")
            viewModelScope.launch {
                llmEngine.initialize()
            }
        }
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
        if (taskId == "semantic_engine") {
            com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationService.activeEngine.value?.stop()
            removeActionWidget(taskId)
            return
        }

        scheduledJobs[taskId]?.cancel()
        scheduledJobs.remove(taskId)
        addMessage(OmniChatMessage(
            text = "⏹️ Scheduled task stopped.",
            isUser = false,
            mode = ResponseMode.SCHEDULED
        ))
    }

    private fun handleDeviceAutomation(result: IntentResult) {
        if (llmEngine.connectionStatus.value != ServerConnectionStatus.CONNECTED) {
            addMessage(OmniChatMessage(
                text = "⚠️ No LLM server connected. Tap ⚙️ above to connect to your Ollama server.",
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

        // Observe the service's active engine status to update the chat message
        viewModelScope.launch {
            // Wait for the engine to become available (service starts asynchronously)
            var engine: com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine? = null
            for (i in 1..20) { // Wait up to 10 seconds
                engine = com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationService.activeEngine.value
                if (engine != null) break
                kotlinx.coroutines.delay(500)
            }
            if (engine == null) {
                updateLastBotMessage(
                    "⚠️ Automation service didn't start. Check permissions.",
                    ResponseMode.AGENT
                )
                return@launch
            }

            // Use transformWhile to STOP collecting after a terminal state.
            // Plain return@collect does NOT cancel StateFlow collection, so the
            // collector would keep running and see CANCELLED when the service
            // destroys itself — overwriting the COMPLETED message.
            engine.status.transformWhile { status ->
                emit(status)
                // Continue collecting only while NOT in a terminal state
                status != com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus.COMPLETED &&
                status != com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus.FAILED &&
                status != com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus.CANCELLED
            }.collect { status ->
                when (status) {
                    com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus.COMPLETED -> {
                        updateLastBotMessage(
                            "✅ Automation completed successfully.",
                            ResponseMode.AGENT,
                            clearWidget = true
                        )
                    }
                    com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus.FAILED -> {
                        val desc = engine.lastActionDescription.value ?: "Unknown error"
                        updateLastBotMessage(
                            "❌ Automation failed: $desc",
                            ResponseMode.AGENT,
                            clearWidget = true
                        )
                    }
                    com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus.CANCELLED -> {
                        updateLastBotMessage(
                            "⏹️ Automation cancelled.",
                            ResponseMode.AGENT,
                            clearWidget = true
                        )
                    }
                    com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus.EXECUTING_ACTION -> {
                        val desc = engine.lastActionDescription.value
                        if (!desc.isNullOrBlank()) {
                            updateLastBotMessage(
                                "🤖 $desc",
                                ResponseMode.AGENT,
                                actionWidget = ActionWidget.StopButton("semantic_engine"),
                                streaming = true
                            )
                        }
                    }
                    else -> { /* Still in progress */ }
                }
            }
        }
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
        // Fallback to Q&A if FAQ intent triggers
        handleQAndA(result)
    }

    private fun handleQAndA(result: IntentResult) {
        addMessage(OmniChatMessage(
            text = "💬 Let me think about that...",
            isUser = false,
            mode = ResponseMode.CHAT,
            isStreaming = true
        ))

        viewModelScope.launch {
            // Retrieve top 3 relevant chunks (filtered by min similarity 0.35)
            val chunks = knowledgeStore.search(result.rawPrompt, topK = 3)

            // Case A: No knowledge chunks found at all
            if (chunks.isEmpty()) {
                updateLastBotMessage(
                    "I don't have information about that in my knowledge base. " +
                    "Try asking about app features, automation, or troubleshooting!",
                    ResponseMode.KNOWLEDGE
                )
                return@launch
            }

            // Combine up to 3 chunks, truncating total to ~2500 chars for context window
            val contextText = buildString {
                for ((i, chunk) in chunks.withIndex()) {
                    if (this.length > 2500) break
                    if (i > 0) append("\n---\n")
                    append(chunk.text.take(900))
                }
            }

            // Case B: Chunks found but no LLM — show clean fallback
            if (llmEngine.connectionStatus.value != ServerConnectionStatus.CONNECTED) {
                val cleanText = cleanKnowledgeChunk(chunks.first().text.take(1000), maxLength = 600)
                val fallback = buildString {
                    append(cleanText)
                    append("\n\n💡 Connect to an LLM server in ⚙️ settings for better answers.")
                }
                updateLastBotMessage(fallback, ResponseMode.KNOWLEDGE)
                return@launch
            }

            // Case C: Full RAG + LLM synthesis (plain text, no JSON schema)
            // /no_think disables Qwen3's internal reasoning mode, which otherwise
            // consumes all output tokens on <think> tags and produces no answer.
            val systemPrompt = buildString {
                append("/no_think\n")
                append("You are Autonion, an AI assistant built into an Android automation app.\n\n")
                append("STRICT RULES:\n")
                append("1. Answer ONLY using the knowledge provided below. Do NOT add information that is not in the knowledge.\n")
                append("2. If the knowledge below does NOT contain information to answer the question, say exactly: \"I don't have specific information about that in my knowledge base.\"\n")
                append("3. Do NOT make up features, capabilities, or instructions that are not explicitly described in the knowledge.\n")
                append("4. Be concise and direct. Use bullet points where appropriate.\n")
                append("5. Do NOT use <think> tags or internal reasoning. Answer immediately.\n\n")
                append("KNOWLEDGE:\n")
                append(contextText)
            }

            val answer = llmEngine.chatForQA(systemPrompt, result.rawPrompt)

            if (!answer.isNullOrBlank()) {
                updateLastBotMessage(answer, ResponseMode.KNOWLEDGE)
            } else {
                // LLM failed — show clean chunk fallback
                val fallback = cleanKnowledgeChunk(chunks.first().text.take(1000), maxLength = 600)
                updateLastBotMessage(
                    "$fallback\n\n💡 LLM didn't respond. Showing knowledge base excerpt.",
                    ResponseMode.KNOWLEDGE
                )
            }
        }
    }

    /**
     * Cleans up a raw knowledge chunk for display as a fallback answer.
     * Removes excessive markdown formatting, normalizes whitespace,
     * and truncates cleanly at sentence boundaries.
     */
    private fun cleanKnowledgeChunk(raw: String, maxLength: Int = 600): String {
        var text = raw.trim()
        // Remove markdown headers (## title)
        text = text.replace(Regex("^#{1,4}\\s+", RegexOption.MULTILINE), "")
        // Collapse multiple newlines into double newline
        text = text.replace(Regex("\n{3,}"), "\n\n")
        // Clean up bullet formatting: normalize "- " to "• "
        text = text.replace(Regex("^-\\s+", RegexOption.MULTILINE), "• ")
        // Remove stray markdown bold/italic artifacts leaving broken text
        text = text.replace(Regex("\\*{2,}"), "")

        if (text.length <= maxLength) return text

        // Truncate at last sentence boundary before maxLength
        val truncated = text.take(maxLength)
        val lastPeriod = truncated.lastIndexOf('.')
        val lastNewline = truncated.lastIndexOf('\n')
        val cutPoint = maxOf(lastPeriod, lastNewline)

        return if (cutPoint > maxLength / 2) {
            truncated.substring(0, cutPoint + 1).trim() + "\n\n…"
        } else {
            truncated.trim() + "…"
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

    private fun updateLastBotMessage(
        text: String, 
        mode: ResponseMode, 
        streaming: Boolean = false,
        actionWidget: ActionWidget? = null,
        clearWidget: Boolean = false
    ) {
        val current = _messages.value.toMutableList()
        val lastBotIdx = current.indexOfFirst { !it.isUser }
        if (lastBotIdx >= 0) {
            current[lastBotIdx] = current[lastBotIdx].copy(
                text = text,
                mode = mode,
                isStreaming = streaming,
                actionWidget = if (clearWidget) null else actionWidget ?: current[lastBotIdx].actionWidget
            )
            _messages.value = current
        } else {
            addMessage(OmniChatMessage(
                text = text, 
                isUser = false, 
                mode = mode, 
                isStreaming = streaming,
                actionWidget = actionWidget
            ))
        }
    }

    private fun removeActionWidget(taskId: String) {
        val current = _messages.value.toMutableList()
        val idx = current.indexOfFirst { !it.isUser && (it.actionWidget as? ActionWidget.StopButton)?.taskId == taskId }
        if (idx >= 0) {
            current[idx] = current[idx].copy(actionWidget = null)
            _messages.value = current
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
