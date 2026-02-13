package com.autonion.automationcompanion.features.cross_device_automation.rules

import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.actions.ActionExecutor
import com.autonion.automationcompanion.features.cross_device_automation.domain.EnrichedEvent
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleCondition
import com.autonion.automationcompanion.features.cross_device_automation.event_pipeline.EventBus
import com.autonion.automationcompanion.features.cross_device_automation.state.StateChangeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RuleEngine(
    private val ruleRepository: RuleRepository, 
    private val actionExecutor: ActionExecutor,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    init {
        scope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is EnrichedEvent -> evaluate(event)
                    is StateChangeEvent -> evaluateStateChange(event)
                }
            }
        }
    }

    private suspend fun evaluate(event: EnrichedEvent) {
        val rules = ruleRepository.getAllRules().first()
        
        rules.filter { it.isEnabled }.forEach { rule ->
            // 1. Check Trigger
            if (rule.trigger.eventType == event.originalEvent.type) {
                // 2. Check Conditions
                if (checkConditions(rule.conditions, event)) {
                    // 3. Execute Actions
                    Log.d("RuleEngine", "Rule matched (Event): ${rule.name}")
                    rule.actions.forEach { action ->
                        actionExecutor.execute(action)
                    }
                }
            }
        }
    }
    
    private suspend fun evaluateStateChange(stateEvent: StateChangeEvent) {
        Log.d("RuleEngine", "Evaluating State Change: ${stateEvent.key} -> ${stateEvent.newValue}")
        // Future: Implement State-based Rules here
        // e.g. If state.context == "meeting" -> Enable DND
    }

    private fun checkConditions(conditions: List<RuleCondition>, event: EnrichedEvent): Boolean {
        if (conditions.isEmpty()) return true
        
        return conditions.all { condition ->
            when (condition) {
                is RuleCondition.TagContains -> {
                    event.detectedTags.contains(condition.tag)
                }
                is RuleCondition.PayloadContains -> {
                    val actualValue = event.originalEvent.payload[condition.key]
                    actualValue != null && actualValue.contains(condition.value, ignoreCase = true)
                }
                is RuleCondition.SourceDevice -> {
                    event.originalEvent.sourceDeviceId == condition.deviceId
                }
            }
        }
    }
}
