package com.autonion.automationcompanion.features.automation.actions.builders

import com.autonion.automationcompanion.features.automation.actions.models.AutomationAction
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction

/**
 * ActionBuilder converts user-configured actions (ConfiguredAction) into executable actions (AutomationAction).
 * This is the single place where validation and conversion logic lives.
 *
 * Design principles:
 * - Trigger-agnostic: no knowledge of location, battery, time, or any other trigger context
 * - Validation-focused: ensures only valid actions are built
 * - Reusable: any trigger type can use this builder
 */
object ActionBuilder {

    /**
     * Converts a list of ConfiguredActions to executable AutomationActions.
     * Invalid actions are filtered out (e.g., SMS with no contacts).
     *
     * @param configuredActions List of configuration states from the user
     * @return List of valid, executable AutomationActions
     */
    fun buildActions(configuredActions: List<ConfiguredAction>): List<AutomationAction> {
        return configuredActions.mapNotNull { config ->
            when (config) {
                is ConfiguredAction.Audio -> {
                    AutomationAction.SetVolume(config.ringVolume, config.mediaVolume)
                }

                is ConfiguredAction.Brightness -> {
                    AutomationAction.SetBrightness(config.level)
                }

                is ConfiguredAction.Dnd -> {
                    AutomationAction.SetDnd(config.enabled)
                }

                is ConfiguredAction.SendSms -> {
                    // Validation: SMS requires non-empty message and at least one contact
                    if (config.message.isNotBlank() && config.contactsCsv.isNotBlank()) {
                        AutomationAction.SendSms(config.message, config.contactsCsv)
                    } else {
                        null  // Filter out invalid SMS configurations
                    }
                }

                // ───────────── Display Actions ─────────────
                is ConfiguredAction.DarkMode -> {
                    AutomationAction.SetDarkMode(config.enabled)
                }

                is ConfiguredAction.AutoRotate -> {
                    AutomationAction.SetAutoRotate(config.enabled)
                }

                is ConfiguredAction.ScreenTimeout -> {
                    AutomationAction.SetScreenTimeout(config.durationMs)
                }

                is ConfiguredAction.NightLight -> {
                    AutomationAction.SetNightLight(config.enabled)
                }

                is ConfiguredAction.KeepScreenAwake -> {
                    AutomationAction.SetKeepScreenAwake(config.enabled)
                }
            }
        }
    }

    /**
     * Validates whether a single ConfiguredAction is valid and can be converted to AutomationAction.
     * Useful for pre-save validation in UI.
     *
     * @param config The configuration to validate
     * @return true if the action is valid, false otherwise
     */
    fun isValid(config: ConfiguredAction): Boolean {
        return when (config) {
            is ConfiguredAction.Audio -> true
            is ConfiguredAction.Brightness -> true
            is ConfiguredAction.Dnd -> true
            is ConfiguredAction.SendSms -> {
                config.message.isNotBlank() && config.contactsCsv.isNotBlank()
            }
            // Display actions: all have safe defaults
            is ConfiguredAction.DarkMode -> true
            is ConfiguredAction.AutoRotate -> true
            is ConfiguredAction.ScreenTimeout -> true
            is ConfiguredAction.NightLight -> true
            is ConfiguredAction.KeepScreenAwake -> true
        }
    }

    /**
     * Checks if at least one valid action exists in the list.
     * Useful for disabling the save button when no actions are configured.
     */
    fun hasAnyValidAction(configuredActions: List<ConfiguredAction>): Boolean {
        return configuredActions.any { isValid(it) }
    }
}
