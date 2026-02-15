package com.autonion.automationcompanion.features.automation.actions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import android.content.Intent

@Composable
fun ActionPicker(
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickContactClicked: (actionIndex: Int) -> Unit,
    onPickAppClicked: (actionIndex: Int) -> Unit,
    dndDisabledReason: String? = null,
    context: android.content.Context
) {
    // --- Permissions State & Launchers ---
    var showWriteSettingsRationale by remember { mutableStateOf(false) }
    var showDndRationale by remember { mutableStateOf(false) }
    var showSmsRationale by remember { mutableStateOf(false) }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.SEND_SMS] == true &&
                      permissions[android.Manifest.permission.READ_CONTACTS] == true
        if (granted) {
            // Permission granted, user can toggle again to enable
        }
    }

    // --- Permission Rationale Dialogs ---
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

    // --- Accent colors for categories ---
    val soundColor = Color(0xFF7C4DFF)   // Purple
    val displayColor = Color(0xFF00B0FF) // Blue
    val commsColor = Color(0xFF00C853)   // Green
    val systemColor = Color(0xFFFF6D00)  // Orange

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {

        /* ═══════════════════════════════════════════════════
         * SECTION 1: SOUND & NOTIFICATIONS
         * ═══════════════════════════════════════════════════ */
        ActionSectionHeader(
            title = "Sound & Notifications",
            icon = Icons.Rounded.VolumeUp,
            iconTint = soundColor
        )

        /* ── Volume & Ringer ── */
        val audioAction = configuredActions.filterIsInstance<ConfiguredAction.Audio>().firstOrNull()
        var audioExpanded by remember(configuredActions) {
            mutableStateOf(audioAction != null)
        }

        ActionRow(
            label = "Set Volume & Ringer",
            subtitle = "Control ring, media & alarm levels",
            icon = Icons.Rounded.VolumeUp,
            iconTint = soundColor,
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

        AnimatedConfigSection(visible = audioAction != null && audioExpanded) {
            AudioActionConfig(
                action = audioAction ?: return@AnimatedConfigSection,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.Audio) updated else it }
                    )
                }
            )
        }

        /* ── Do Not Disturb ── */
        val dndAction = configuredActions.filterIsInstance<ConfiguredAction.Dnd>().firstOrNull()
        val dndDisabled = dndDisabledReason != null

        ActionRow(
            label = "Do Not Disturb",
            subtitle = "Mute all calls & notifications",
            icon = Icons.Rounded.DoNotDisturb,
            iconTint = soundColor,
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
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        /* ── Show Notification ── */
        val notificationAction = configuredActions.filterIsInstance<ConfiguredAction.NotificationAction>().firstOrNull()
        var notificationExpanded by remember(configuredActions) {
            mutableStateOf(notificationAction != null)
        }

        ActionRow(
            label = "Show Notification",
            subtitle = "Display a custom notification",
            icon = Icons.Rounded.Notifications,
            iconTint = soundColor,
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

        AnimatedConfigSection(visible = notificationAction != null && notificationExpanded) {
            NotificationActionConfig(
                action = notificationAction ?: return@AnimatedConfigSection,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.NotificationAction) updated else it }
                    )
                }
            )
        }

        /* ═══════════════════════════════════════════════════
         * SECTION 2: DISPLAY
         * ═══════════════════════════════════════════════════ */
        ActionSectionHeader(
            title = "Display",
            icon = Icons.Rounded.Brightness6,
            iconTint = displayColor
        )

        /* ── Brightness ── */
        val brightnessAction = configuredActions.filterIsInstance<ConfiguredAction.Brightness>().firstOrNull()
        var brightnessExpanded by remember(configuredActions) {
            mutableStateOf(brightnessAction != null)
        }

        ActionRow(
            label = "Set Brightness",
            subtitle = "Adjust screen brightness level",
            icon = Icons.Rounded.Brightness6,
            iconTint = displayColor,
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

        AnimatedConfigSection(visible = brightnessAction != null && brightnessExpanded) {
            BrightnessActionConfig(
                action = brightnessAction ?: return@AnimatedConfigSection,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.Brightness) updated else it }
                    )
                }
            )
        }

        /* ── Display Group (Auto-rotate, Screen Timeout, Keep Awake) ── */
        val hasAnyDisplayAction = configuredActions.any {
            it is ConfiguredAction.AutoRotate ||
            it is ConfiguredAction.ScreenTimeout ||
            it is ConfiguredAction.KeepScreenAwake
        }

        var displayExpanded by remember(configuredActions) {
            mutableStateOf(hasAnyDisplayAction)
        }

        ActionRow(
            label = "Display Controls",
            subtitle = "Auto-rotate, timeout & sleep settings",
            icon = Icons.Rounded.ScreenRotation,
            iconTint = displayColor,
            checked = hasAnyDisplayAction,
            onCheckedChange = { enabled ->
                displayExpanded = enabled
            }
        )

        AnimatedConfigSection(visible = displayExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // ── Auto-rotate ──
                val autoRotateAction =
                    configuredActions.filterIsInstance<ConfiguredAction.AutoRotate>().firstOrNull()

                ActionRow(
                    label = "Auto-rotate",
                    icon = Icons.Rounded.ScreenRotation,
                    iconTint = displayColor,
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

                AnimatedConfigSection(visible = autoRotateAction != null) {
                    AutoRotateActionConfig(
                        action = autoRotateAction ?: return@AnimatedConfigSection,
                        onActionChanged = { updated ->
                            onActionsChanged(
                                configuredActions.map {
                                    if (it is ConfiguredAction.AutoRotate) updated else it
                                }
                            )
                        }
                    )
                }

                // ── Screen Timeout ──
                val screenTimeoutAction =
                    configuredActions.filterIsInstance<ConfiguredAction.ScreenTimeout>().firstOrNull()

                ActionRow(
                    label = "Screen Timeout",
                    icon = Icons.Rounded.Timer,
                    iconTint = displayColor,
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

                AnimatedConfigSection(visible = screenTimeoutAction != null) {
                    ScreenTimeoutActionConfig(
                        action = screenTimeoutAction ?: return@AnimatedConfigSection,
                        onActionChanged = { updated ->
                            onActionsChanged(
                                configuredActions.map {
                                    if (it is ConfiguredAction.ScreenTimeout) updated else it
                                }
                            )
                        }
                    )
                }

                // ── Prevent Sleep ──
                val keepAwakeAction =
                    configuredActions.filterIsInstance<ConfiguredAction.KeepScreenAwake>().firstOrNull()

                ActionRow(
                    label = "Prevent Sleep",
                    icon = Icons.Rounded.Visibility,
                    iconTint = displayColor,
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

                AnimatedConfigSection(visible = keepAwakeAction != null) {
                    KeepScreenAwakeActionConfig(
                        action = keepAwakeAction ?: return@AnimatedConfigSection,
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

        /* ═══════════════════════════════════════════════════
         * SECTION 3: COMMUNICATION
         * ═══════════════════════════════════════════════════ */
        ActionSectionHeader(
            title = "Communication",
            icon = Icons.Rounded.Message,
            iconTint = commsColor
        )

        /* ── Send SMS ── */
        val smsAction = configuredActions.filterIsInstance<ConfiguredAction.SendSms>().firstOrNull()
        val smsIndex = configuredActions.indexOfFirst { it is ConfiguredAction.SendSms }
        var smsExpanded by remember(configuredActions) {
            mutableStateOf(smsAction != null)
        }

        ActionRow(
            label = "Send Message",
            subtitle = "Auto-send SMS to a contact",
            icon = Icons.Rounded.Message,
            iconTint = commsColor,
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

        AnimatedConfigSection(visible = smsAction != null && smsExpanded && smsIndex >= 0) {
            SmsActionConfig(
                action = smsAction ?: return@AnimatedConfigSection,
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

        /* ═══════════════════════════════════════════════════
         * SECTION 4: SYSTEM
         * ═══════════════════════════════════════════════════ */
        ActionSectionHeader(
            title = "System",
            icon = Icons.Rounded.Settings,
            iconTint = systemColor
        )

        /* ── App Action ── */
        val appAction = configuredActions.filterIsInstance<ConfiguredAction.AppAction>().firstOrNull()
        val appActionIndex = configuredActions.indexOfFirst { it is ConfiguredAction.AppAction }
        var appExpanded by remember(configuredActions) {
            mutableStateOf(appAction != null)
        }

        ActionRow(
            label = "App Action",
            subtitle = "Launch or control an app",
            icon = Icons.Rounded.Apps,
            iconTint = systemColor,
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

        AnimatedConfigSection(visible = appAction != null && appExpanded && appActionIndex >= 0) {
            AppActionConfig(
                context = context,
                action = appAction ?: return@AnimatedConfigSection,
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

        /* ── Battery Saver ── */
        val batterySaverAction = configuredActions.filterIsInstance<ConfiguredAction.BatterySaver>().firstOrNull()

        ActionRow(
            label = "Battery Saver",
            subtitle = "Toggle battery saving mode",
            icon = Icons.Rounded.BatterySaver,
            iconTint = systemColor,
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

        AnimatedConfigSection(visible = batterySaverAction != null) {
            BatterySaverActionConfig(
                action = batterySaverAction ?: return@AnimatedConfigSection,
                onActionChanged = { updated ->
                    onActionsChanged(
                        configuredActions.map { if (it is ConfiguredAction.BatterySaver) updated else it }
                    )
                }
            )
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
