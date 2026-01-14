package com.autonion.automationcompanion.features.automation.actions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction

/**
 * ActionPicker is a trigger-agnostic, reusable UI component for configuring actions.
 *
 * Design:
 * - Manages all action configuration UI in one place
 * - Does NOT know about location, battery, time, or any trigger context
 * - Can be plugged into any trigger type (location, battery, app state, etc.)
 * - Each action is independently collapsible/expandable
 *
 * @param configuredActions Current list of configured actions
 * @param onActionsChanged Callback when actions are added/modified/removed
 * @param onPickContactClicked Callback when user wants to pick a contact (activity-level concern)
 * @param dndDisabledReason Optional reason why DND is disabled (e.g., "Disabled when Volume is active")
 */
@Composable
fun ActionPicker(
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickContactClicked: (actionIndex: Int) -> Unit,
    dndDisabledReason: String? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Automations", style = MaterialTheme.typography.titleMedium)

        // ───────────── Audio (Volume) ─────────────
        var audioExpanded by remember {
            mutableStateOf(
                configuredActions.any { it is ConfiguredAction.Audio }
            )
        }
        val audioAction = configuredActions.filterIsInstance<ConfiguredAction.Audio>().firstOrNull()

        ActionRow(
            label = "Set Volume",
            checked = audioAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    // Add default audio action if not present
                    if (audioAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.Audio(
                                ringVolume = 3,
                                mediaVolume = 8
                            )
                        )
                    }
                    audioExpanded = true
                } else {
                    // Remove audio action
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.Audio })
                    audioExpanded = false
                }
            }
        )

        if (audioAction != null && audioExpanded) {
            AudioActionConfig(
                action = audioAction,
                onActionChanged = { updatedAction ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.Audio) updatedAction else it }
                    )
                }
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // ───────────── Brightness ─────────────
        var brightnessExpanded by remember {
            mutableStateOf(
                configuredActions.any { it is ConfiguredAction.Brightness }
            )
        }
        val brightnessAction = configuredActions.filterIsInstance<ConfiguredAction.Brightness>().firstOrNull()

        ActionRow(
            label = "Set Brightness",
            checked = brightnessAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (brightnessAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.Brightness(level = 150)
                        )
                    }
                    brightnessExpanded = true
                } else {
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.Brightness })
                    brightnessExpanded = false
                }
            }
        )

        if (brightnessAction != null && brightnessExpanded) {
            BrightnessActionConfig(
                action = brightnessAction,
                onActionChanged = { updatedAction ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.Brightness) updatedAction else it }
                    )
                }
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // ───────────── DND ─────────────
        // Note: DND may be disabled due to hardware conflicts (e.g., Realme ROM)
        val dndDisabled = dndDisabledReason != null
        val dndAction = configuredActions.filterIsInstance<ConfiguredAction.Dnd>().firstOrNull()

        ActionRow(
            label = "Do Not Disturb",
            checked = dndAction != null,
            enabled = !dndDisabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (dndAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.Dnd(enabled = true)
                        )
                    }
                } else {
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.Dnd })
                }
            }
        )

        if (dndDisabledReason != null) {
            Text(
                dndDisabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp)
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // ───────────── Send SMS ─────────────
        var smsExpanded by remember {
            mutableStateOf(
                configuredActions.any { it is ConfiguredAction.SendSms }
            )
        }
        val smsAction = configuredActions.filterIsInstance<ConfiguredAction.SendSms>().firstOrNull()
        val smsIndex = configuredActions.indexOfFirst { it is ConfiguredAction.SendSms }

        ActionRow(
            label = "Send Message",
            checked = smsAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (smsAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.SendSms(
                                message = "",
                                contactsCsv = ""
                            )
                        )
                    }
                    smsExpanded = true
                } else {
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.SendSms })
                    smsExpanded = false
                }
            }
        )

        if (smsAction != null && smsExpanded) {
            SmsActionConfig(
                action = smsAction,
                onActionChanged = { updatedAction ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.SendSms) updatedAction else it }
                    )
                },
                onPickContactClicked = {
                    onPickContactClicked(smsIndex)
                }
            )
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // ───────────── Display (Master Group) ─────────────
        var displayExpanded by remember {
            mutableStateOf(
                configuredActions.any {
                    it is ConfiguredAction.DarkMode ||
                            it is ConfiguredAction.AutoRotate ||
                            it is ConfiguredAction.ScreenTimeout ||
                            it is ConfiguredAction.NightLight ||
                            it is ConfiguredAction.KeepScreenAwake
                }
            )
        }

        ActionRow(
            label = "Display",
            checked = displayExpanded,
            onCheckedChange = { enabled ->
                if (!enabled) {
                    // Remove all Display actions
                    onActionsChanged(
                        configuredActions.filterNot {
                            it is ConfiguredAction.DarkMode ||
                                    it is ConfiguredAction.AutoRotate ||
                                    it is ConfiguredAction.ScreenTimeout ||
                                    it is ConfiguredAction.NightLight ||
                                    it is ConfiguredAction.KeepScreenAwake
                        }
                    )
                    displayExpanded = false
                } else {
                    displayExpanded = true
                }
            }
        )

        if (displayExpanded) {
            Column(Modifier.padding(start = 12.dp)) {
                // ───── Dark Mode ─────
                val darkModeAction = configuredActions.filterIsInstance<ConfiguredAction.DarkMode>().firstOrNull()
                var darkModeExpanded by remember {
                    mutableStateOf(darkModeAction != null)
                }

                ActionRow(
                    label = "Dark Mode",
                    checked = darkModeAction != null,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (darkModeAction == null) {
                                onActionsChanged(
                                    configuredActions + ConfiguredAction.DarkMode(enabled = true)
                                )
                            }
                            darkModeExpanded = true
                        } else {
                            onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.DarkMode })
                            darkModeExpanded = false
                        }
                    }
                )

                if (darkModeAction != null && darkModeExpanded) {
                    DarkModeActionConfig(
                        action = darkModeAction,
                        onActionChanged = { updatedAction ->
                            onActionsChanged(
                                configuredActions.map { if (it is ConfiguredAction.DarkMode) updatedAction else it }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ───── Auto-rotate ─────
                val autoRotateAction = configuredActions.filterIsInstance<ConfiguredAction.AutoRotate>().firstOrNull()
                var autoRotateExpanded by remember {
                    mutableStateOf(autoRotateAction != null)
                }

                ActionRow(
                    label = "Auto-rotate",
                    checked = autoRotateAction != null,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (autoRotateAction == null) {
                                onActionsChanged(
                                    configuredActions + ConfiguredAction.AutoRotate(enabled = true)
                                )
                            }
                            autoRotateExpanded = true
                        } else {
                            onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.AutoRotate })
                            autoRotateExpanded = false
                        }
                    }
                )

                if (autoRotateAction != null && autoRotateExpanded) {
                    AutoRotateActionConfig(
                        action = autoRotateAction,
                        onActionChanged = { updatedAction ->
                            onActionsChanged(
                                configuredActions.map { if (it is ConfiguredAction.AutoRotate) updatedAction else it }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ───── Screen Timeout ─────
                val screenTimeoutAction = configuredActions.filterIsInstance<ConfiguredAction.ScreenTimeout>().firstOrNull()
                var screenTimeoutExpanded by remember {
                    mutableStateOf(screenTimeoutAction != null)
                }

                ActionRow(
                    label = "Screen Timeout",
                    checked = screenTimeoutAction != null,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (screenTimeoutAction == null) {
                                onActionsChanged(
                                    configuredActions + ConfiguredAction.ScreenTimeout(durationMs = 30_000)
                                )
                            }
                            screenTimeoutExpanded = true
                        } else {
                            onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.ScreenTimeout })
                            screenTimeoutExpanded = false
                        }
                    }
                )

                if (screenTimeoutAction != null && screenTimeoutExpanded) {
                    ScreenTimeoutActionConfig(
                        action = screenTimeoutAction,
                        onActionChanged = { updatedAction ->
                            onActionsChanged(
                                configuredActions.map { if (it is ConfiguredAction.ScreenTimeout) updatedAction else it }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ───── Night Light ─────
                val nightLightAction = configuredActions.filterIsInstance<ConfiguredAction.NightLight>().firstOrNull()
                var nightLightExpanded by remember {
                    mutableStateOf(nightLightAction != null)
                }

                ActionRow(
                    label = "Night Light",
                    checked = nightLightAction != null,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (nightLightAction == null) {
                                onActionsChanged(
                                    configuredActions + ConfiguredAction.NightLight(enabled = true)
                                )
                            }
                            nightLightExpanded = true
                        } else {
                            onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.NightLight })
                            nightLightExpanded = false
                        }
                    }
                )

                if (nightLightAction != null && nightLightExpanded) {
                    NightLightActionConfig(
                        action = nightLightAction,
                        onActionChanged = { updatedAction ->
                            onActionsChanged(
                                configuredActions.map { if (it is ConfiguredAction.NightLight) updatedAction else it }
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ───── Keep Screen Awake ─────
                val keepScreenAwakeAction = configuredActions.filterIsInstance<ConfiguredAction.KeepScreenAwake>().firstOrNull()
                var keepScreenAwakeExpanded by remember {
                    mutableStateOf(keepScreenAwakeAction != null)
                }

                ActionRow(
                    label = "Keep Screen Awake",
                    checked = keepScreenAwakeAction != null,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            if (keepScreenAwakeAction == null) {
                                onActionsChanged(
                                    configuredActions + ConfiguredAction.KeepScreenAwake(enabled = true)
                                )
                            }
                            keepScreenAwakeExpanded = true
                        } else {
                            onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.KeepScreenAwake })
                            keepScreenAwakeExpanded = false
                        }
                    }
                )

                if (keepScreenAwakeAction != null && keepScreenAwakeExpanded) {
                    KeepScreenAwakeActionConfig(
                        action = keepScreenAwakeAction,
                        onActionChanged = { updatedAction ->
                            onActionsChanged(
                                configuredActions.map { if (it is ConfiguredAction.KeepScreenAwake) updatedAction else it }
                            )
                        }
                    )
                }
            }
        }
    }
}
