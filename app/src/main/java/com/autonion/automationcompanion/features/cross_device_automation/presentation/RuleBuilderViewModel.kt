package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationRule
import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleAction
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleCondition
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class RuleBuilderViewModel(
    private val manager: CrossDeviceAutomationManager
) : ViewModel() {

    private val _rules = MutableStateFlow<List<AutomationRule>>(emptyList())
    val rules: StateFlow<List<AutomationRule>> = _rules.asStateFlow()
    
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    init {
        viewModelScope.launch {
            manager.ruleRepository.getAllRules().collect {
                _rules.value = it
            }
        }
        viewModelScope.launch {
            manager.deviceRepository.getAllDevices().collect {
                _devices.value = it
            }
        }
    }

    fun addRule(
        name: String,
        triggerType: String,
        conditions: List<RuleCondition>,
        actions: List<RuleAction>
    ) {
        viewModelScope.launch {
            val rule = AutomationRule(
                id = UUID.randomUUID().toString(),
                name = name,
                trigger = RuleTrigger(triggerType),
                conditions = conditions,
                actions = actions
            )
            manager.ruleRepository.addRule(rule)
        }
    }
    
    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            manager.ruleRepository.deleteRule(ruleId)
        }
    }
}
