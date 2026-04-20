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

    init {
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
            val prompt = AutomationPrompt(
                transactionId = UUID.randomUUID().toString(),
                prompt = promptText,
                timestamp = System.currentTimeMillis()
            )

            // Add user message
            addMessage(ChatMessage(text = promptText, isUser = true))

            if (parseHardwareMapping(promptText)) {
                _inputQuery.value = ""
                return@launch
            }

            // Broadcast to all connected devices (Desktop Agent)
            manager.networkingManager.broadcast(prompt)
            
            _isAutomationActive.value = true

            // Clear input
            _inputQuery.value = ""
        }
    }
    
    fun stopAutomation() {
        _isAutomationActive.value = false
        addMessage(ChatMessage(text = "Stopping automation...", isUser = false))
        manager.stopRemoteAutomation()
    }
    
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

    private fun addMessage(message: ChatMessage) {
        val current = _messages.value.toMutableList()
        current.add(0, message) // Add to top (for reverseLayout)
        _messages.value = current
    }
}
