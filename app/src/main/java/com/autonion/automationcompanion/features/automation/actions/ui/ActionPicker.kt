package com.autonion.automationcompanion.features.automation.actions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction

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

        /* -------------------- AUDIO -------------------- */

        val audioAction = configuredActions.filterIsInstance<ConfiguredAction.Audio>().firstOrNull()
        var audioExpanded by remember(configuredActions) {
            mutableStateOf(audioAction != null)
        }

        ActionRow(
            label = "Set Volume",
            checked = audioAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (audioAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.Audio(3, 8)
                        )
                    }
                    audioExpanded = true
                } else {
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.Audio })
                    audioExpanded = false
                }
            }
        )

        if (audioAction != null && audioExpanded) {
            AudioActionConfig(
                action = audioAction,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.Audio) updated else it }
                    )
                }
            )
        }

        HorizontalDivider()

        /* -------------------- BRIGHTNESS -------------------- */

        val brightnessAction = configuredActions.filterIsInstance<ConfiguredAction.Brightness>().firstOrNull()
        var brightnessExpanded by remember(configuredActions) {
            mutableStateOf(brightnessAction != null)
        }

        ActionRow(
            label = "Set Brightness",
            checked = brightnessAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (brightnessAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.Brightness(150)
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
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.Brightness) updated else it }
                    )
                }
            )
        }

        HorizontalDivider()

        /* -------------------- DND -------------------- */

        val dndAction = configuredActions.filterIsInstance<ConfiguredAction.Dnd>().firstOrNull()
        val dndDisabled = dndDisabledReason != null

        ActionRow(
            label = "Do Not Disturb",
            checked = dndAction != null,
            enabled = !dndDisabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (dndAction == null) {
                        onActionsChanged(configuredActions + ConfiguredAction.Dnd(true))
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

        HorizontalDivider()

        /* -------------------- SMS -------------------- */

        val smsAction = configuredActions.filterIsInstance<ConfiguredAction.SendSms>().firstOrNull()
        val smsIndex = configuredActions.indexOfFirst { it is ConfiguredAction.SendSms }
        var smsExpanded by remember(configuredActions) {
            mutableStateOf(smsAction != null)
        }

        ActionRow(
            label = "Send Message",
            checked = smsAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (smsAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.SendSms("", "")
                        )
                    }
                    smsExpanded = true
                } else {
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.SendSms })
                    smsExpanded = false
                }
            }
        )

        if (smsAction != null && smsExpanded && smsIndex >= 0) {
            SmsActionConfig(
                action = smsAction,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.SendSms) updated else it }
                    )
                },
                onPickContactClicked = {
                    onPickContactClicked(smsIndex)
                }
            )
        }

        HorizontalDivider()

        /* -------------------- DISPLAY (GROUP) -------------------- */

        val hasAnyDisplayAction = configuredActions.any {
                    it is ConfiguredAction.AutoRotate ||
                    it is ConfiguredAction.ScreenTimeout ||
                    it is ConfiguredAction.KeepScreenAwake
        }

        var displayExpanded by remember(configuredActions) {
            mutableStateOf(hasAnyDisplayAction)
        }

        ActionRow(
            label = "Display",
            checked = hasAnyDisplayAction,
            onCheckedChange = { enabled ->
                displayExpanded = enabled
            }
        )

        if (displayExpanded) {

            // ───── Auto-rotate ─────
            val autoRotateAction =
                configuredActions.filterIsInstance<ConfiguredAction.AutoRotate>().firstOrNull()

            ActionRow(
                label = "Auto-rotate",
                checked = autoRotateAction != null,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        if (autoRotateAction == null) {
                            onActionsChanged(configuredActions + ConfiguredAction.AutoRotate(true))
                        }
                    } else {
                        onActionsChanged(
                            configuredActions.filterNot { it is ConfiguredAction.AutoRotate }
                        )
                    }
                }
            )

            if (autoRotateAction != null) {
                AutoRotateActionConfig(
                    action = autoRotateAction,
                    onActionChanged = { updated ->
                        onActionsChanged(
                            configuredActions.map {
                                if (it is ConfiguredAction.AutoRotate) updated else it
                            }
                        )
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // ───── Screen Timeout ─────
            val screenTimeoutAction =
                configuredActions.filterIsInstance<ConfiguredAction.ScreenTimeout>().firstOrNull()

            ActionRow(
                label = "Screen Timeout",
                checked = screenTimeoutAction != null,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        if (screenTimeoutAction == null) {
                            onActionsChanged(
                                configuredActions + ConfiguredAction.ScreenTimeout(30_000)
                            )
                        }
                    } else {
                        onActionsChanged(
                            configuredActions.filterNot { it is ConfiguredAction.ScreenTimeout }
                        )
                    }
                }
            )

            if (screenTimeoutAction != null) {
                ScreenTimeoutActionConfig(
                    action = screenTimeoutAction,
                    onActionChanged = { updated ->
                        onActionsChanged(
                            configuredActions.map {
                                if (it is ConfiguredAction.ScreenTimeout) updated else it
                            }
                        )
                    }
                )
            }

            Spacer(Modifier.height(8.dp))

            // ───── Prevent Sleep ─────
            val keepAwakeAction =
                configuredActions.filterIsInstance<ConfiguredAction.KeepScreenAwake>().firstOrNull()

            ActionRow(
                label = "Prevent Sleep",
                checked = keepAwakeAction != null,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        if (keepAwakeAction == null) {
                            onActionsChanged(
                                configuredActions + ConfiguredAction.KeepScreenAwake(true)
                            )
                        }
                    } else {
                        onActionsChanged(
                            configuredActions.filterNot { it is ConfiguredAction.KeepScreenAwake }
                        )
                    }
                }
            )

            if (keepAwakeAction != null) {
                KeepScreenAwakeActionConfig(
                    action = keepAwakeAction,
                    onActionChanged = { updated ->
                        onActionsChanged(
                            configuredActions.map {
                                if (it is ConfiguredAction.KeepScreenAwake) updated else it
                            }
                        )
                    }
                )
            }
        }


    }
}

/* -------------------- HELPERS -------------------- */

@Composable
private inline fun <reified T : ConfiguredAction> DisplayToggle(
    label: String,
    configuredActions: List<ConfiguredAction>,
    crossinline onActionsChanged: (List<ConfiguredAction>) -> Unit,
    crossinline defaultAction: () -> T
) {
    val action = configuredActions.filterIsInstance<T>().firstOrNull()
    var expanded by remember(configuredActions) { mutableStateOf(action != null) }

    ActionRow(
        label = label,
        checked = action != null,
        onCheckedChange = { enabled ->
            if (enabled) {
                if (action == null) {
                    onActionsChanged(configuredActions + defaultAction())
                }
                expanded = true
            } else {
                onActionsChanged(configuredActions.filterNot { it is T })
                expanded = false
            }
        }
    )
}
