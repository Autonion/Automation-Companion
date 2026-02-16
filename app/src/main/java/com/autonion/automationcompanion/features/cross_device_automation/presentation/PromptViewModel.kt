package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class PromptViewModel(
    private val manager: CrossDeviceAutomationManager
) : ViewModel() {

    private val _inputQuery = MutableStateFlow("")
    val inputQuery: StateFlow<String> = _inputQuery.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

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

            // Broadcast to all connected devices (Desktop Agent)
            manager.networkingManager.broadcast(prompt)
            
            // Add system acknowledgment
            addMessage(ChatMessage(text = "Sent to connected devices", isUser = false))

            // Clear input
            _inputQuery.value = ""
        }
    }
    
    private fun addMessage(message: ChatMessage) {
        val current = _messages.value.toMutableList()
        current.add(0, message) // Add to top (for reverseLayout)
        _messages.value = current
    }
}
