package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationRule
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleAction
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleCondition
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleTrigger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class DesktopAutomationViewModel(
    private val manager: CrossDeviceAutomationManager
) : ViewModel() {

    // Filter only rules related to browser navigation
    val rules: StateFlow<List<AutomationRule>> = manager.ruleRepository.getAllRules()
        .map { list -> list.filter { it.trigger.eventType == "browser.navigation" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createRule(
        name: String,
        category: String, // "Meeting", "Social", "Work", or "Custom"
        customUrl: String?,
        configuredActions: List<ConfiguredAction>
    ) {
        val ruleActions = configuredActions.map { mapConfiguredActionToRuleAction(it) }
        
        val conditions = mutableListOf<RuleCondition>()
        
        if (category == "Custom" && !customUrl.isNullOrEmpty()) {
             conditions.add(RuleCondition.PayloadContains("url", customUrl))
        } else {
             conditions.add(RuleCondition.PayloadContains("category", category.lowercase()))
        }

        val rule = AutomationRule(
            id = UUID.randomUUID().toString(),
            name = name,
            trigger = RuleTrigger("browser.navigation"),
            conditions = conditions,
            actions = ruleActions
        )

        viewModelScope.launch {
            manager.ruleRepository.addRule(rule)
            manager.syncRulesToDesktop()
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            manager.ruleRepository.deleteRule(ruleId)
            manager.syncRulesToDesktop()
        }
    }

    private fun mapConfiguredActionToRuleAction(action: ConfiguredAction): RuleAction {
        // Mapping logic from ConfiguredAction (Sealed Class) to RuleAction (Data Class)
        return when (action) {
            is ConfiguredAction.Dnd -> RuleAction("enable_dnd", mapOf("enabled" to action.enabled.toString()))
            is ConfiguredAction.Audio -> {
                 RuleAction("set_volume", mapOf(
                     "ring_volume" to action.ringVolume.toString(),
                     "media_volume" to action.mediaVolume.toString(),
                     "alarm_volume" to action.alarmVolume.toString(),
                     "ringer_mode" to action.ringerMode.name
                 ))
            }
            is ConfiguredAction.Brightness -> RuleAction("set_brightness", mapOf("level" to action.level.toString()))
            is ConfiguredAction.SendSms -> RuleAction("send_sms", mapOf(
                "message" to action.message,
                "contacts_csv" to action.contactsCsv
            ))
            is ConfiguredAction.AppAction -> RuleAction("launch_app", mapOf(
                "package_name" to action.packageName,
                "action_type" to action.actionType.name
            ))
            is ConfiguredAction.AutoRotate -> RuleAction("set_auto_rotate", mapOf("enabled" to action.enabled.toString()))
            is ConfiguredAction.ScreenTimeout -> RuleAction("set_screen_timeout", mapOf("duration_ms" to action.durationMs.toString()))
            is ConfiguredAction.BatterySaver -> RuleAction("set_battery_saver", mapOf("enabled" to action.enabled.toString()))
            // Fallback for types not strictly in Executor yet, though covered above
            is ConfiguredAction.NotificationAction -> RuleAction("send_notification", mapOf(
                "title" to action.title,
                "message" to action.text
            ))
            is ConfiguredAction.KeepScreenAwake -> RuleAction("set_screen_timeout", mapOf("duration_ms" to (if (action.enabled) Int.MAX_VALUE else 30000).toString()))
        }
    }
}
