package com.autonion.automationcompanion.features.cross_device_automation.data

import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationRule
import com.autonion.automationcompanion.features.cross_device_automation.rules.RuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class InMemoryRuleRepository : RuleRepository {
    private val _rules = MutableStateFlow<List<AutomationRule>>(emptyList())

    override fun getAllRules(): Flow<List<AutomationRule>> = _rules.asStateFlow()

    override suspend fun addRule(rule: AutomationRule) {
        _rules.update { it + rule }
    }

    override suspend fun deleteRule(ruleId: String) {
        _rules.update { list -> list.filterNot { it.id == ruleId } }
    }
}
