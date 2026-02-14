package com.autonion.automationcompanion.features.automation.actions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.automation.actions.models.AppActionType
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.models.NotificationType
import com.autonion.automationcompanion.features.automation.actions.models.RingerMode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import android.provider.Settings
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.content.Intent

@Composable
fun ActionPicker(
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickContactClicked: (actionIndex: Int) -> Unit,
    onPickAppClicked: (actionIndex: Int) -> Unit, // NEW
    dndDisabledReason: String? = null,
    context: android.content.Context
) {
    // --- Permissions State & Launchers ---
    var showWriteSettingsRationale by remember { mutableStateOf(false) }
    var showDndRationale by remember { mutableStateOf(false) }
    var showSmsRationale by remember { mutableStateOf(false) }

    // Launcher for System Settings (StartActivityForResult doesn't return result for settings, so we just check on return)
    // We actually just launch the intent, and rely on the user navigating back.
    // However, to know *which* permission we were asking for to auto-enable, we could track state.
    // For simplicity, we just launch settings and let the user toggle again.

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.SEND_SMS] == true &&
                      permissions[android.Manifest.permission.READ_CONTACTS] == true
        if (granted) {
            // Permission granted, user can toggle again to enable
        }
    }

    if (showWriteSettingsRationale) {
        AlertDialog(
            onDismissRequest = { showWriteSettingsRationale = false },
            title = { Text("Permission Required") },
            text = { Text("To control brightness, auto-rotate, or screen timeout, this app needs permission to Modify System Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showWriteSettingsRationale = false
                    PermissionUtils.requestWriteSettingsPermission(context)
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showWriteSettingsRationale = false }) { Text("Cancel") }
            }
        )
    }

    if (showDndRationale) {
        AlertDialog(
            onDismissRequest = { showDndRationale = false },
            title = { Text("Permission Required") },
            text = { Text("To control Do Not Disturb and Volume, this app needs 'Do Not Disturb Access'.") },
            confirmButton = {
                TextButton(onClick = {
                    showDndRationale = false
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showDndRationale = false }) { Text("Cancel") }
            }
        )
    }

    if (showSmsRationale) {
        AlertDialog(
            onDismissRequest = { showSmsRationale = false },
            title = { Text("Permission Required") },
            text = { Text("To send messages, this app needs SMS and Contacts permissions.") },
            confirmButton = {
                TextButton(onClick = {
                    showSmsRationale = false
                    smsPermissionLauncher.launch(
                        arrayOf(
                            android.Manifest.permission.SEND_SMS,
                            android.Manifest.permission.READ_CONTACTS
                        )
                    )
                }) { Text("Grant") }
            },
            dismissButton = {
                TextButton(onClick = { showSmsRationale = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Automations", style = MaterialTheme.typography.titleMedium)

        /* -------------------- AUDIO (UPDATED) -------------------- */
        val audioAction = configuredActions.filterIsInstance<ConfiguredAction.Audio>().firstOrNull()
        var audioExpanded by remember(configuredActions) {
            mutableStateOf(audioAction != null)
        }

        ActionRow(
            label = "Set Volume & Ringer",
            checked = audioAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (!nm.isNotificationPolicyAccessGranted) {
                        showDndRationale = true
                    } else {
                        if (audioAction == null) {
                            onActionsChanged(
                                configuredActions + ConfiguredAction.Audio(
                                    ringVolume = 3,
                                    mediaVolume = 8,
                                    alarmVolume = 8,
                                    ringerMode = RingerMode.NORMAL
                                )
                            )
                        }
                        audioExpanded = true
                    }
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
                    if (!PermissionUtils.isWriteSettingsPermissionGranted(context)) {
                        showWriteSettingsRationale = true
                    } else {
                        if (brightnessAction == null) {
                            onActionsChanged(
                                configuredActions + ConfiguredAction.Brightness(150)
                            )
                        }
                        brightnessExpanded = true
                    }
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
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (!nm.isNotificationPolicyAccessGranted) {
                        showDndRationale = true
                    } else {
                        if (dndAction == null) {
                            onActionsChanged(configuredActions + ConfiguredAction.Dnd(true))
                        }
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
                    val hasSms = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val hasContact = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    
                    if (!hasSms || !hasContact) {
                        showSmsRationale = true
                    } else {
                        if (smsAction == null) {
                            onActionsChanged(
                                configuredActions + ConfiguredAction.SendSms("", "")
                            )
                        }
                        smsExpanded = true
                    }
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
        /* -------------------- APP ACTION -------------------- */
        val appAction = configuredActions.filterIsInstance<ConfiguredAction.AppAction>().firstOrNull()
        val appActionIndex = configuredActions.indexOfFirst { it is ConfiguredAction.AppAction }
        var appExpanded by remember(configuredActions) {
            mutableStateOf(appAction != null)
        }

        ActionRow(
            label = "App Action",
            checked = appAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (appAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.AppAction(
                                packageName = "",
                                actionType = AppActionType.LAUNCH
                            )
                        )
                    }
                    appExpanded = true
                } else {
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.AppAction })
                    appExpanded = false
                }
            }
        )

        if (appAction != null && appExpanded && appActionIndex >= 0) {
            AppActionConfig(
                context = context,
                action = appAction,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.AppAction) updated else it }
                    )
                },
                onPickAppClicked = {
                    onPickAppClicked(appActionIndex)
                }
            )
        }

        HorizontalDivider()

        /* -------------------- NOTIFICATION -------------------- */
        val notificationAction = configuredActions.filterIsInstance<ConfiguredAction.NotificationAction>().firstOrNull()
        var notificationExpanded by remember(configuredActions) {
            mutableStateOf(notificationAction != null)
        }

        ActionRow(
            label = "Show Notification",
            checked = notificationAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (notificationAction == null) {
                        onActionsChanged(
                            configuredActions + ConfiguredAction.NotificationAction(
                                title = "",
                                text = "",
                                notificationType = NotificationType.NORMAL
                            )
                        )
                    }
                    notificationExpanded = true
                } else {
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.NotificationAction })
                    notificationExpanded = false
                }
            }
        )

        if (notificationAction != null && notificationExpanded) {
            NotificationActionConfig(
                action = notificationAction,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.NotificationAction) updated else it }
                    )
                }
            )
        }

        HorizontalDivider()

        /* -------------------- BATTERY SAVER -------------------- */
        val batterySaverAction = configuredActions.filterIsInstance<ConfiguredAction.BatterySaver>().firstOrNull()

        ActionRow(
            label = "Battery Saver",
            checked = batterySaverAction != null,
            onCheckedChange = { enabled ->
                if (enabled) {
                    if (batterySaverAction == null) {
                        onActionsChanged(configuredActions + ConfiguredAction.BatterySaver(false))
                    }
                } else {
                    onActionsChanged(configuredActions.filterNot { it is ConfiguredAction.BatterySaver })
                }
            }
        )

        if (batterySaverAction != null) {
            BatterySaverActionConfig(
                action = batterySaverAction,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.BatterySaver) updated else it }
                    )
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
                        if (!PermissionUtils.isWriteSettingsPermissionGranted(context)) {
                            showWriteSettingsRationale = true
                        } else {
                            if (autoRotateAction == null) {
                                onActionsChanged(configuredActions + ConfiguredAction.AutoRotate(true))
                            }
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
                        if (!PermissionUtils.isWriteSettingsPermissionGranted(context)) {
                            showWriteSettingsRationale = true
                        } else {
                            if (screenTimeoutAction == null) {
                                onActionsChanged(
                                    configuredActions + ConfiguredAction.ScreenTimeout(30_000)
                                )
                            }
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
