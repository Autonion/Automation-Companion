package com.autonion.automationcompanion.features.omni_chatbot

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import com.autonion.automationcompanion.features.omni_chatbot.companion.FeatureMatcher
import com.autonion.automationcompanion.features.omni_chatbot.companion.WalkthroughRegistry
import com.autonion.automationcompanion.features.omni_chatbot.companion.WalkthroughScript
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.UUID
import dev.langchain4j.memory.ChatMemory
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.ollama.OllamaChatModel
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatSessionEntity
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatMessageEntity

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

    private val omniChatDao = AppDatabase.get(context).omniChatDao()

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

    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory.asStateFlow()

    val isAIReady: StateFlow<Boolean> = combine(
        llmConnectionStatus,
        inferenceMode
    ) { status, mode ->
        if (mode == com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.SERVER_LLM) {
            status == ServerConnectionStatus.CONNECTED
        } else {
            true // SLM mode doesn't strictly depend on server connection
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ─── State ──────────────────────────────────────────────
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _messages = MutableStateFlow<List<OmniChatMessage>>(emptyList())
    val messages: StateFlow<List<OmniChatMessage>> = _messages.asStateFlow()

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    private val _currentRoute = MutableStateFlow<String?>("home")
    val currentRoute: StateFlow<String?> = _currentRoute.asStateFlow()

    private val _chatSessionId = MutableStateFlow(UUID.randomUUID().toString())
    val chatSessionId: StateFlow<String> = _chatSessionId.asStateFlow()

    private val _chatHistorySessions = MutableStateFlow<List<OmniChatSessionEntity>>(emptyList())
    val chatHistorySessions: StateFlow<List<OmniChatSessionEntity>> = _chatHistorySessions.asStateFlow()

    /** Active scheduled task jobs, keyed by task ID */
    private val scheduledJobs = mutableMapOf<String, Job>()

    /** Track completed transactions to prevent duplicate completion messages */
    private val completedTransactions = mutableSetOf<String>()

    // ─── RAG + FAQ (initialized synchronously, loaded asynchronously) ─────
    private val faqRepository = FAQRepository()
    private val knowledgeStore = KnowledgeStore(intentClassifier.embedder)

    private val ragPromptBuilder = RAGPromptBuilder()

    // ─── Langchain4j Chat Memory ────────────────────────────
    private val chatMemory: ChatMemory = MessageWindowChatMemory.withMaxMessages(20)

    private fun getLangchainModel(): ChatLanguageModel? {
        val url = llmEngine.serverUrl.value
        val modelName = llmEngine.selectedModelName.value
        if (url.isBlank() || modelName.isBlank()) return null
        
        val baseUrl = if (url.endsWith("/")) url else "$url/"
        return OllamaChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(modelName)
            .temperature(0.3)
            .build()
    }

    // ─── FAQ Browser State ──────────────────────────────────
    private val _showFAQBrowser = MutableStateFlow(false)
    val showFAQBrowser: StateFlow<Boolean> = _showFAQBrowser.asStateFlow()

    private val _faqList = MutableStateFlow<List<FAQRepository.FAQ>>(emptyList())
    val faqList: StateFlow<List<FAQRepository.FAQ>> = _faqList.asStateFlow()

    // ─── Companion Walkthrough State ──────────────────────────
    private val _activeWalkthrough = MutableStateFlow<WalkthroughScript?>(null)
    val activeWalkthrough: StateFlow<WalkthroughScript?> = _activeWalkthrough.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigationEvent: SharedFlow<String> = _navigationEvent

    // ─── Init: Wire up Desktop response flow ────────────────
    init {
        viewModelScope.launch {
            faqRepository.loadFAQs(context)
            _faqList.value = faqRepository.getAllFAQs()
            
            knowledgeStore.loadFromAssets(context)
        }

        viewModelScope.launch {
            omniChatDao.getAllSessions().collect { sessions ->
                _chatHistorySessions.value = sessions
            }
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

    fun clearChat() {
        chatMemory.clear()
        _messages.value = emptyList()
        _chatSessionId.value = UUID.randomUUID().toString()
        _showHistory.value = false
    }

    fun toggleHistory() {
        _showHistory.value = !_showHistory.value
        if (_showHistory.value) _showSettings.value = false
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            omniChatDao.deleteSession(sessionId)
        }
    }

    fun loadChatSession(sessionId: String) {
        viewModelScope.launch {
            val dbMessages = omniChatDao.getMessagesForSession(sessionId)
            _chatSessionId.value = sessionId
            val mappedMessages = dbMessages.map { entity ->
                OmniChatMessage(
                    id = entity.messageId,
                    text = entity.text,
                    isUser = entity.isUser,
                    mode = ResponseMode.valueOf(entity.mode),
                    timestamp = entity.timestamp,
                    actionWidget = null,
                    suggestedWalkthroughId = entity.suggestedWalkthroughId
                )
            }.reversed() // Reverse to match the UI's newest-first order
            
            _messages.value = mappedMessages
            
            // Rebuild memory
            chatMemory.clear()
            mappedMessages.reversed().forEach { msg ->
                if (msg.isUser) chatMemory.add(UserMessage(msg.text))
                else chatMemory.add(AiMessage(msg.text))
            }
        }
    }

    fun expand() {
        _isExpanded.value = true
        llmEngine.autoConnectIfNeeded()
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

    // ─── Main Entry Point ───────────────────────────────────

    fun processPrompt(text: String? = null) {
        val prompt = (text ?: _inputText.value).trim()
        if (prompt.isBlank()) return

        _inputText.value = ""

        // Add user message
        addMessage(OmniChatMessage(text = prompt, isUser = true))

        // Early walkthrough check removed - now handled via Q&A appending.

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
            val setupMessage = buildString {
                append("⚠️ It looks like Ollama isn't running.\n\n")
                append("To use on-device AI automation, you need Ollama running on your desktop:\n\n")
                append("1️⃣ Install Ollama from ollama.com (if not installed)\n")
                append("2️⃣ Open a terminal and run: ollama serve\n")
                append("3️⃣ Pull a model: ollama pull qwen3\n")
                append("4️⃣ Tap ⚙️ above and enter your PC's IP address to connect\n\n")
                append("💡 Make sure your phone and PC are on the same WiFi network.")
            }
            addMessage(OmniChatMessage(
                text = setupMessage,
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
            // Wait for the knowledge store to finish loading (handles the race
            // condition where the user sends a question before background init
            // completes — previously this returned "I don't have information").
            if (!knowledgeStore.isLoaded) {
                Log.d(TAG, "Q&A: Knowledge store still loading, waiting...")
                val waitStart = System.currentTimeMillis()
                while (!knowledgeStore.isLoaded && System.currentTimeMillis() - waitStart < 10_000) {
                    delay(200)
                }
                if (!knowledgeStore.isLoaded) {
                    Log.w(TAG, "Q&A: Knowledge store didn't load within 10s")
                    updateLastBotMessage(
                        "⏳ Knowledge base is still loading. Please try again in a moment.",
                        ResponseMode.KNOWLEDGE
                    )
                    return@launch
                }
                Log.d(TAG, "Q&A: Knowledge store ready after ${System.currentTimeMillis() - waitStart}ms")
            }

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
                    append("\n\n💡 For smarter answers, connect to Ollama:\n")
                    append("• Run \"ollama serve\" on your PC\n")
                    append("• Tap ⚙️ above and enter your PC's IP address")
                }
                updateLastBotMessage(fallback, ResponseMode.KNOWLEDGE)
                return@launch
            }

            // ── Stable system prompt — identity + rules only, NEVER changes ──
            // This mirrors how ChatGPT/Gemini work: constant system identity
            // with conversation history flowing naturally between turns.
            val baseSystemPrompt = buildString {
                append("/no_think\n")
                append("You are Autonion, an AI assistant built into an Android automation app.\n\n")
                append("STRICT RULES:\n")
                append("1. Answer ONLY using the reference knowledge provided for each question. Do NOT add information that is not in the knowledge.\n")
                append("2. If the reference knowledge does NOT contain information to answer the question, say exactly: \"I don't have specific information about that in my knowledge base.\"\n")
                append("3. Do NOT make up features, capabilities, or instructions that are not explicitly described in the knowledge.\n")
                append("4. Be concise and direct. Use bullet points where appropriate.\n")
                append("5. Do NOT use <think> tags or internal reasoning. Answer immediately.\n")
                append("6. If your answer is primarily about one of these features, append the tag on a new line at the very end of your response: [WALKTHROUGH:feature_id]\n")
                append("   Available features: flow_builder, gesture_recording, semantic_automation, cross_device, visual_trigger, screen_ml, system_context, debugger\n")
                append("   IMPORTANT: Do NOT append a WALKTHROUGH tag for browser extension, extension installation, or extension setup topics. Those have no walkthrough.\n")
                append("7. IMPORTANT: There are TWO different extensions. The 'Autonion Extension' is for Desktop PC browsers. The 'Autonion Android Extension' is for Mobile phone browsers. If the user asks about an 'extension' without specifying PC or Mobile, explicitly mention both, explain the difference, and you MUST provide the exact github.com download URLs for BOTH extensions exactly as they appear in the knowledge below.\n")
            }

            // ── Per-query knowledge — scoped to current question only ──
            // Placed right before the user's question so the model knows
            // this knowledge is for THIS turn, not for the conversation history.
            val knowledgeContext = buildString {
                append("REFERENCE KNOWLEDGE FOR THE FOLLOWING QUESTION ONLY:\n")
                append("(Use this knowledge to answer the user's next message. ")
                append("Do NOT apply it to previous conversation topics.)\n\n")
                append(contextText)
            }

            var rawAnswer: String? = null
            try {
                withContext(Dispatchers.IO) {
                    val model = getLangchainModel()
                    if (model != null) {
                        val userMsg = UserMessage(result.rawPrompt)
                        
                        // Message ordering mirrors ChatGPT architecture:
                        // 1. Stable system identity (constant across turns)
                        // 2. Full conversation history (natural flow)
                        // 3. Per-query knowledge (scoped to current question)
                        // 4. Current user question
                        val allMessages = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
                        allMessages.add(SystemMessage(baseSystemPrompt))
                        allMessages.addAll(chatMemory.messages())
                        allMessages.add(SystemMessage(knowledgeContext))
                        allMessages.add(userMsg)
                        
                        val response = model.generate(allMessages)
                        var content = response.content().text().trim()
                        
                        if (content.contains("</think>")) {
                            content = content.substringAfter("</think>").trim()
                        } else if (content.startsWith("<think>")) {
                            content = ""
                        }
                        
                        if (content.isNotBlank()) {
                            rawAnswer = content
                            chatMemory.add(userMsg)
                            chatMemory.add(AiMessage(rawAnswer))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Langchain Q&A generation failed", e)
            }

            // Parse [WALKTHROUGH:feature_id] tag from the LLM response.
            // The LLM appends this when the answer is clearly about a specific feature.
            // Also catch bare [feature_id] tags that the LLM sometimes produces.
            val walkthroughTagRegex = Regex("""\[WALKTHROUGH:(\w+)]""")
            val bareTagRegex = Regex("""\[(flow_builder|gesture_recording|semantic_automation|cross_device|visual_trigger|screen_ml|system_context|debugger)]""")
            val tagMatch = rawAnswer?.let { walkthroughTagRegex.find(it) }
            val bareTagMatch = rawAnswer?.let { bareTagRegex.find(it) }
            val llmSuggestedFeature = tagMatch?.groupValues?.get(1)
                ?: bareTagMatch?.groupValues?.get(1)

            // Strip both tag formats from the displayed answer
            val answer = rawAnswer
                ?.let { walkthroughTagRegex.replace(it, "") }
                ?.let { bareTagRegex.replace(it, "") }
                ?.trim()

            // Priority: prompt-based match → LLM tag → null
            // If the query is about an excluded topic (e.g. extensions), suppress ALL
            // walkthrough suggestions — even hallucinated LLM tags.
            val walkthroughFeature = if (FeatureMatcher.isExcludedFromWalkthrough(result.rawPrompt)) {
                null
            } else {
                FeatureMatcher.matchFeature(result.rawPrompt) ?: llmSuggestedFeature
            }

            if (!answer.isNullOrBlank()) {
                updateLastBotMessage(answer, ResponseMode.KNOWLEDGE, suggestedWalkthroughId = walkthroughFeature)
            } else {
                // LLM failed — show clean chunk fallback
                val fallback = cleanKnowledgeChunk(chunks.first().text.take(1000), maxLength = 600)
                updateLastBotMessage(
                    "$fallback\n\n💡 LLM didn't respond. Showing knowledge base excerpt.",
                    ResponseMode.KNOWLEDGE,
                    suggestedWalkthroughId = walkthroughFeature
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

        val text = when {
            response.status == ResponseStatus.FAILED && response.message.contains("browser", ignoreCase = true) -> {
                "❌ Browser automation failed.\n\n" +
                "🔧 Troubleshooting:\n" +
                "• Make sure the Autonion Extension is installed and enabled in your browser\n" +
                "• Ensure the browser is open and running\n" +
                "• Try refreshing the extension or restarting the browser"
            }
            response.status == ResponseStatus.FAILED && response.message.contains("extension", ignoreCase = true) -> {
                "❌ Could not connect to the browser extension.\n\n" +
                "🔧 Steps to fix:\n" +
                "• Open Chrome/Edge and check that the Autonion Extension is enabled\n" +
                "• Click the extension icon to verify it shows \"Connected\"\n" +
                "• Restart the browser if the issue persists"
            }
            response.status == ResponseStatus.FAILED -> "$emoji ${response.message}"
            else -> "$emoji ${response.message}"
        }

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

    private fun persistSessionAndMessage(message: OmniChatMessage) {
        viewModelScope.launch {
            val sessionId = _chatSessionId.value
            val currentMessages = _messages.value
            
            val sessionEntity = OmniChatSessionEntity(
                sessionId = sessionId,
                title = currentMessages.lastOrNull { it.isUser }?.text?.take(30) ?: "New Chat",
                timestamp = System.currentTimeMillis(),
                previewText = message.text.take(50)
            )
            omniChatDao.insertSession(sessionEntity)
            
            val messageEntity = OmniChatMessageEntity(
                messageId = message.id,
                sessionId = sessionId,
                text = message.text,
                isUser = message.isUser,
                mode = message.mode.name,
                timestamp = message.timestamp,
                actionWidgetJson = null,
                suggestedWalkthroughId = message.suggestedWalkthroughId
            )
            omniChatDao.insertMessage(messageEntity)
        }
    }

    private fun addMessage(message: OmniChatMessage) {
        val cleaned = if (!message.isUser) message.copy(text = stripMarkdown(message.text)) else message
        val current = _messages.value.toMutableList()
        current.add(0, cleaned) // Newest first (for reverseLayout)
        _messages.value = current
        persistSessionAndMessage(cleaned)
    }

    private fun updateLastBotMessage(
        text: String, 
        mode: ResponseMode, 
        streaming: Boolean = false,
        actionWidget: ActionWidget? = null,
        clearWidget: Boolean = false,
        suggestedWalkthroughId: String? = null
    ) {
        val cleanedText = stripMarkdown(text)
        val current = _messages.value.toMutableList()
        val lastBotIdx = current.indexOfFirst { !it.isUser }
        if (lastBotIdx >= 0) {
            current[lastBotIdx] = current[lastBotIdx].copy(
                text = cleanedText,
                mode = mode,
                isStreaming = streaming,
                actionWidget = if (clearWidget) null else actionWidget ?: current[lastBotIdx].actionWidget,
                suggestedWalkthroughId = suggestedWalkthroughId ?: current[lastBotIdx].suggestedWalkthroughId
            )
            _messages.value = current
            persistSessionAndMessage(current[lastBotIdx])
        } else {
            val msg = OmniChatMessage(
                text = cleanedText, 
                isUser = false, 
                mode = mode, 
                isStreaming = streaming,
                actionWidget = actionWidget,
                suggestedWalkthroughId = suggestedWalkthroughId
            )
            addMessage(msg)
        }
    }

    /** Strip common markdown formatting so chat bubbles show clean plaintext. */
    private fun stripMarkdown(text: String): String {
        return text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")   // **bold**
            .replace(Regex("__(.+?)__"), "$1")             // __bold__
            .replace(Regex("\\*(.+?)\\*"), "$1")           // *italic*
            .replace(Regex("_(.+?)_"), "$1")               // _italic_
            .replace(Regex("~~(.+?)~~"), "$1")             // ~~strikethrough~~
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "") // # headings
            .replace(Regex("`(.+?)`"), "$1")               // `inline code`
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

    // ═══════════════════════════════════════════════════════════
    //  COMPANION WALKTHROUGH
    // ═══════════════════════════════════════════════════════════

    /**
     * Start a guided walkthrough for the given feature.
     * Collapses the chat sheet and navigates to the feature's first screen.
     */
    fun startWalkthrough(featureId: String) {
        val script = WalkthroughRegistry.getScript(featureId)
        if (script == null) {
            addMessage(OmniChatMessage(
                text = "Sorry, I don't have a guided walkthrough for that feature yet.",
                isUser = false,
                mode = ResponseMode.SYSTEM
            ))
            return
        }

        _activeWalkthrough.value = script
        _currentStepIndex.value = 0
        collapse() // Hide the chat sheet

        // Add companion message to chat
        addMessage(OmniChatMessage(
            text = "\uD83E\uDDED Starting guided walkthrough: ${script.featureName}\n${script.description}",
            isUser = false,
            mode = ResponseMode.COMPANION
        ))

        // Navigate to the first step's route — but only if we're not already there.
        // Without this check, pressing the walkthrough icon from inside the feature
        // re-pushes the same route, causing an abrupt slide-in animation.
        val firstStep = script.steps.firstOrNull()
        firstStep?.targetRoute?.let { route ->
            val current = _currentRoute.value
            if (current == null || !current.contains(route.substringAfterLast("/"))) {
                _navigationEvent.tryEmit(route)
            }
        }

        Log.d(TAG, "Walkthrough started: ${script.featureId} (${script.steps.size} steps)")
    }

    /** Advance to the next walkthrough step. */
    fun nextWalkthroughStep() {
        val script = _activeWalkthrough.value ?: return
        val nextIndex = _currentStepIndex.value + 1
        if (nextIndex >= script.steps.size) {
            // Reached the end
            dismissWalkthrough()
            return
        }
        _currentStepIndex.value = nextIndex

        // Navigate if the step has a target route and we're not already there
        script.steps[nextIndex].targetRoute?.let { route ->
            val current = _currentRoute.value
            if (current == null || !current.contains(route.substringAfterLast("/"))) {
                _navigationEvent.tryEmit(route)
            }
        }
    }

    /** Go back to the previous walkthrough step. */
    fun previousWalkthroughStep() {
        val currentIndex = _currentStepIndex.value
        if (currentIndex > 0) {
            _currentStepIndex.value = currentIndex - 1

            val script = _activeWalkthrough.value ?: return
            script.steps[currentIndex - 1].targetRoute?.let { route ->
                val current = _currentRoute.value
                if (current == null || !current.contains(route.substringAfterLast("/"))) {
                    _navigationEvent.tryEmit(route)
                }
            }
        }
    }

    /** Dismiss the active walkthrough and return to normal mode. */
    fun dismissWalkthrough() {
        val wasActive = _activeWalkthrough.value != null
        _activeWalkthrough.value = null
        _currentStepIndex.value = 0

        if (wasActive) {
            addMessage(OmniChatMessage(
                text = "Walkthrough complete! Feel free to ask me anything else. \uD83D\uDE0A",
                isUser = false,
                mode = ResponseMode.COMPANION
            ))
            Log.d(TAG, "Walkthrough dismissed")
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel all scheduled tasks
        scheduledJobs.values.forEach { it.cancel() }
        scheduledJobs.clear()
    }
}
