package com.autonion.automationcompanion.features.cross_device_automation.rules

import android.util.Log
import com.autonion.automationcompanion.features.cross_device_automation.actions.ActionExecutor
import com.autonion.automationcompanion.features.cross_device_automation.domain.EnrichedEvent
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleCondition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class RuleEngine(
    private val ruleRepository: RuleRepository, // Need to define this
    private val actionExecutor: ActionExecutor
) {

    suspend fun evaluate(event: EnrichedEvent) {
        val rules = ruleRepository.getAllRules().first()
        
        rules.filter { it.isEnabled }.forEach { rule ->
            // 1. Check Trigger
            if (rule.trigger.eventType == event.originalEvent.type) {
                // 2. Check Conditions
                if (checkConditions(rule.conditions, event)) {
                    // 3. Execute Actions
                    Log.d("RuleEngine", "Rule matched: ${rule.name}")
                    rule.actions.forEach { action ->
                        actionExecutor.execute(action)
                    }
                }
            }
        }
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
