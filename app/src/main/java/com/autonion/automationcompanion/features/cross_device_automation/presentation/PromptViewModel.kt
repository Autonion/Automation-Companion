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

class PromptViewModel(
    private val manager: CrossDeviceAutomationManager
) : ViewModel() {

    private val _inputQuery = MutableStateFlow("")
    val inputQuery: StateFlow<String> = _inputQuery.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

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

            // Log locally
            appendLog("Sent: $promptText")
            
            // Broadcast to all connected devices (Desktop Agent)
            // We use 'broadcast' because we don't strictly know which device is the "agent" yet, 
            // and we want to reach any listeners.
            manager.networkingManager.broadcast(prompt)
            
            // Clear input
            _inputQuery.value = ""
        }
    }
    
    private fun appendLog(message: String) {
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, message) // Add to top
        _logs.value = currentLogs
    }
}
