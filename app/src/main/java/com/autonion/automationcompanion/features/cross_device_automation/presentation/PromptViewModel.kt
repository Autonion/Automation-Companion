package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationPrompt
import com.autonion.automationcompanion.features.cross_device_automation.domain.ResponseStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import android.content.Context
import com.autonion.automationcompanion.features.cross_device_automation.engine.DesktopAction
import com.autonion.automationcompanion.features.cross_device_automation.engine.GestureType
import com.autonion.automationcompanion.features.cross_device_automation.engine.HardwareButtonMapper
import com.autonion.automationcompanion.features.semantic_automation.memory.AutomationChatMemory
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatSessionEntity
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatMessageEntity
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import android.view.KeyEvent

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class PromptViewModel(
    private val manager: CrossDeviceAutomationManager,
    private val context: Context
) : ViewModel() {

    private val _inputQuery = MutableStateFlow("")
    val inputQuery: StateFlow<String> = _inputQuery.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    
    private val _isAutomationActive = MutableStateFlow(false)
    val isAutomationActive: StateFlow<Boolean> = _isAutomationActive.asStateFlow()

    private val chatMemory = AutomationChatMemory.getInstance(context)
    private val chatDao = AppDatabase.get(context).omniChatDao()

    // ─── Session & History State ────────────────────────────
    private val _chatSessionId = MutableStateFlow(UUID.randomUUID().toString())

    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory.asStateFlow()

    private val _chatHistorySessions = MutableStateFlow<List<OmniChatSessionEntity>>(emptyList())
    val chatHistorySessions: StateFlow<List<OmniChatSessionEntity>> = _chatHistorySessions.asStateFlow()

    init {
        // Collect chat history sessions for this module
        viewModelScope.launch {
            chatDao.getSessionsByModule("cross_device").collect { sessions ->
                _chatHistorySessions.value = sessions
            }
        }

        viewModelScope.launch {
            try {
                manager.networkingManager.responseFlow.collect { response ->
                    val statusText = when (response.status) {
                        ResponseStatus.STARTED -> "Started"
                        ResponseStatus.IN_PROGRESS -> "In Progress"
                        ResponseStatus.COMPLETED -> "Completed"
                        ResponseStatus.FAILED -> "Failed"
                        ResponseStatus.CANCELLED -> "Cancelled"
                        else -> response.status.name.lowercase().replaceFirstChar { it.uppercase() }
                    }
                    
                    if (response.status == ResponseStatus.COMPLETED || response.status == ResponseStatus.FAILED || response.status == ResponseStatus.CANCELLED) {
                        _isAutomationActive.value = false
                    }

                    addMessage(ChatMessage(
                        text = "[$statusText] ${response.message}",
                        isUser = false
                    ))
                }
            } catch (e: Exception) {
                // Ignore or log
            }
        }
    }

    fun onQueryChanged(newQuery: String) {
        _inputQuery.value = newQuery
    }

    fun sendPrompt() {
        val promptText = _inputQuery.value
        if (promptText.isBlank()) return

        viewModelScope.launch {
            // Add user message
            addMessage(ChatMessage(text = promptText, isUser = true))

            // ── 1. Hardware Remote mapping (volume buttons) ──
            if (parseHardwareMapping(promptText)) {
                _inputQuery.value = ""
                return@launch
            }

            // ── 2. Direct keyboard intent (no LLM needed) ──
            val directKeys = parseKeyboardIntent(promptText)
            if (directKeys != null) {
                broadcastKeyPress(directKeys)
                val keyLabel = directKeys.joinToString("+")
                addMessage(ChatMessage(text = "⌨️ Pressed $keyLabel", isUser = false))
                _inputQuery.value = ""
                return@launch
            }

            // ── 3. Full prompt → broadcast to Desktop Agent (LLM path) ──
            val prompt = AutomationPrompt(
                transactionId = UUID.randomUUID().toString(),
                prompt = promptText,
                timestamp = System.currentTimeMillis(),
                context = chatMemory.buildContextSummary()
            )

            manager.networkingManager.broadcast(prompt)
            _isAutomationActive.value = true
            _inputQuery.value = ""
        }
    }
    
    fun stopAutomation() {
        _isAutomationActive.value = false
        addMessage(ChatMessage(text = "Stopping automation...", isUser = false))
        manager.stopRemoteAutomation()
    }

    // ─── Direct Keyboard Intent Parser ──────────────────────────
    // Recognizes simple key press commands like "press escape", "click enter",
    // "hit tab", "press ctrl+shift+esc", "press win+v" etc. and sends them
    // directly as structured key_press commands without going through the LLM.

    private val keyMap = mapOf(
        "escape" to "Escape", "esc" to "Escape",
        "enter" to "Return", "return" to "Return",
        "tab" to "Tab",
        "space" to "Space", "spacebar" to "Space",
        "backspace" to "Backspace",
        "delete" to "Delete", "del" to "Delete",
        "home" to "Home", "end" to "End",
        "page up" to "Page_Up", "pageup" to "Page_Up",
        "page down" to "Page_Down", "pagedown" to "Page_Down",
        "up arrow" to "Up", "down arrow" to "Down",
        "left arrow" to "Left", "right arrow" to "Right",
        "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right",
        "f1" to "F1", "f2" to "F2", "f3" to "F3", "f4" to "F4",
        "f5" to "F5", "f6" to "F6", "f7" to "F7", "f8" to "F8",
        "f9" to "F9", "f10" to "F10", "f11" to "F11", "f12" to "F12",
        "caps lock" to "Caps_Lock", "capslock" to "Caps_Lock",
        "print screen" to "Print_Screen", "printscreen" to "Print_Screen",
        "prtsc" to "Print_Screen", "prtscn" to "Print_Screen",
        "insert" to "Insert", "ins" to "Insert",
        "pause" to "Pause", "break" to "Pause",
        "num lock" to "Num_Lock", "numlock" to "Num_Lock",
        "scroll lock" to "Scroll_Lock", "scrolllock" to "Scroll_Lock",
        "windows" to "win", "win" to "win",
        "alt" to "alt", "ctrl" to "ctrl", "control" to "ctrl",
        "shift" to "shift",
        "menu" to "Menu", "context menu" to "Menu",
        "a" to "a", "b" to "b", "c" to "c", "d" to "d", "e" to "e",
        "f" to "f", "g" to "g", "h" to "h", "i" to "i", "j" to "j",
        "k" to "k", "l" to "l", "m" to "m", "n" to "n", "o" to "o",
        "p" to "p", "q" to "q", "r" to "r", "s" to "s", "t" to "t",
        "u" to "u", "v" to "v", "w" to "w", "x" to "x", "y" to "y",
        "z" to "z",
        "0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4",
        "5" to "5", "6" to "6", "7" to "7", "8" to "8", "9" to "9",
        "minus" to "Minus", "-" to "Minus",
        "plus" to "Plus", "=" to "Equal", "equals" to "Equal",
        "[" to "[", "]" to "]",
        "\\" to "\\", "/" to "/",
        ";" to ";", "'" to "'", "," to ",", "." to ".",
        "`" to "`", "tilde" to "`",
        "volume up" to "volumeup", "volume down" to "volumedown",
        "volume mute" to "volumemute", "mute" to "volumemute",
        "play pause" to "playpause", "play" to "playpause",
        "next track" to "nexttrack", "prev track" to "prevtrack",
        "stop" to "stop",
    )

    // Match patterns like: "press escape", "click on the enter key",
    // "hit the tab button", "tap space", "push delete",
    // "press ctrl+shift+esc", "press win+v"
    private val keyCommandRegex = Regex(
        "^(?:please\\s+)?(?:press|click|hit|tap|push|type)\\s+" +
        "(?:on\\s+)?(?:the\\s+)?(.+?)(?:\\s+(?:button|key|btn))?\\s*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parses a prompt for keyboard intent.
     * Returns a list of key names for combos (e.g. ["ctrl", "shift", "Escape"]),
     * a single-element list for single keys, or null if no match.
     */
    private fun parseKeyboardIntent(text: String): List<String>? {
        val lower = text.lowercase().trim()
        val match = keyCommandRegex.find(lower) ?: return null
        val captured = match.groupValues[1].trim()

        // Check for key combo patterns: "ctrl+shift+esc", "win+v", "alt+f4"
        if (captured.contains("+")) {
            val parts = captured.split("+").map { it.trim() }
            val resolvedKeys = parts.map { part -> keyMap[part] ?: return null }
            return resolvedKeys
        }

        // Single key lookup
        val resolved = keyMap[captured] ?: return null
        return listOf(resolved)
    }

    private fun broadcastKeyPress(keys: List<String>) {
        val command = mapOf(
            "type" to "key_press",
            "keys" to keys,
            "keyName" to keys.joinToString("+"),  // backward compat
            "transactionId" to UUID.randomUUID().toString()
        )
        manager.networkingManager.broadcast(command)
    }

    // ─── Hardware Remote Parser ─────────────────────────────────
    
    private fun parseHardwareMapping(promptText: String): Boolean {
        val lowerText = promptText.lowercase()
        if (!lowerText.contains("volume up") && !lowerText.contains("volume down")) return false

        val mappings = mutableMapOf<Pair<Int, GestureType>, DesktopAction>()
        
        val parts = lowerText.split(Regex("and|,|\\."))
        
        for (part in parts) {
            val keyCode = if (part.contains("volume up")) KeyEvent.KEYCODE_VOLUME_UP 
                          else if (part.contains("volume down")) KeyEvent.KEYCODE_VOLUME_DOWN 
                          else continue
                          
            val gesture = if (part.contains("double press") || part.contains("double tap")) GestureType.DOUBLE_TAP
                          else if (part.contains("long press") || part.contains("hold")) GestureType.LONG_PRESS
                          else GestureType.SINGLE_TAP
                          
            val keyMatch = Regex("(send|click|press)\\s+([a-z]+)").find(part)
            if (keyMatch != null) {
                val keyName = keyMatch.groupValues[2]
                mappings[Pair(keyCode, gesture)] = DesktopAction.SendKey(keyName.replaceFirstChar { it.uppercase() })
            }
        }
        
        if (mappings.isNotEmpty()) {
            HardwareButtonMapper.activate(context, mappings)
            addMessage(ChatMessage(text = "✅ Hardware Remote Activated\nRunning in background. Screen can be turned off.", isUser = false))
            return true
        }
        return false
    }

    // ─── Chat History & Session Management ───────────────────

    fun clearChat() {
        chatMemory.clearSession()
        _messages.value = emptyList()
        _chatSessionId.value = UUID.randomUUID().toString()
        _showHistory.value = false
    }

    fun toggleHistory() {
        _showHistory.value = !_showHistory.value
    }

    fun loadChatSession(sessionId: String) {
        viewModelScope.launch {
            val dbMessages = chatDao.getMessagesForSession(sessionId)
            _chatSessionId.value = sessionId
            val mappedMessages = dbMessages.map { entity ->
                ChatMessage(
                    id = entity.messageId,
                    text = entity.text,
                    isUser = entity.isUser,
                    timestamp = entity.timestamp
                )
            }.reversed()
            _messages.value = mappedMessages
            _showHistory.value = false
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            chatDao.deleteSession(sessionId)
        }
    }

    private fun persistSessionAndMessage(message: ChatMessage) {
        viewModelScope.launch {
            val sessionId = _chatSessionId.value
            val currentMessages = _messages.value
            
            val sessionEntity = OmniChatSessionEntity(
                sessionId = sessionId,
                title = currentMessages.lastOrNull { it.isUser }?.text?.take(30) ?: "New Chat",
                timestamp = System.currentTimeMillis(),
                previewText = message.text.take(50),
                module = "cross_device"
            )
            chatDao.insertSession(sessionEntity)
            
            val messageEntity = OmniChatMessageEntity(
                messageId = message.id,
                sessionId = sessionId,
                text = message.text,
                isUser = message.isUser,
                mode = "DIRECT",
                timestamp = message.timestamp,
                actionWidgetJson = null,
                suggestedWalkthroughId = null
            )
            chatDao.insertMessage(messageEntity)
        }
    }

    private fun addMessage(message: ChatMessage) {
        val current = _messages.value.toMutableList()
        current.add(0, message) // Add to top (for reverseLayout)
        _messages.value = current
        persistSessionAndMessage(message)
    }
}
