package com.autonion.automationcompanion.features.cross_device_automation.rules

import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationRule
import kotlinx.coroutines.flow.Flow

interface RuleRepository {
    fun getAllRules(): Flow<List<AutomationRule>>
    suspend fun addRule(rule: AutomationRule)
    suspend fun deleteRule(ruleId: String)
}
