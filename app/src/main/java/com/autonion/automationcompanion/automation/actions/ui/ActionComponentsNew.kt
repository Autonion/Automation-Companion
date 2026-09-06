package com.autonion.automationcompanion.automation.actions.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.automation.actions.models.AppActionType
import com.autonion.automationcompanion.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.automation.actions.models.NotificationType
import com.autonion.automationcompanion.automation.actions.models.RingerMode
import kotlin.math.roundToInt

/**
 * Updated Audio (Volume) action configuration UI.
 * Now includes alarm volume and ringer mode.
 */
@Composable
internal fun AudioActionConfig(
    action: ConfiguredAction.Audio,
    onActionChanged: (ConfiguredAction.Audio) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Ring Volume
        Column {
            Text("Ring Volume: ${action.ringVolume}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = action.ringVolume.toFloat(),
                onValueChange = { newValue ->
                    onActionChanged(action.copy(ringVolume = newValue.roundToInt()))
                },
                valueRange = 0f..7f,
                steps = 6,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Media Volume
        Column {
            Text("Media Volume: ${action.mediaVolume}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = action.mediaVolume.toFloat(),
                onValueChange = { newValue ->
                    onActionChanged(action.copy(mediaVolume = newValue.roundToInt()))
                },
                valueRange = 0f..15f,
                steps = 14,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Alarm Volume
        Column {
            Text("Alarm Volume: ${action.alarmVolume}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = action.alarmVolume.toFloat(),
                onValueChange = { newValue ->
                    onActionChanged(action.copy(alarmVolume = newValue.roundToInt()))
                },
                valueRange = 0f..15f,
                steps = 14,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Ringer Mode
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Ringer Mode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        RingerMode.NORMAL to "Normal",
                        RingerMode.VIBRATE to "Vibrate",
                        RingerMode.SILENT to "Silent"
                    ).forEach { (mode, label) ->
                        val isSelected = action.ringerMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { onActionChanged(action.copy(ringerMode = mode)) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.5.sp
                                ),
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * App Action configuration UI.
 * Shows app picker and action type selection.
 */
@Composable
internal fun AppActionConfig(
    context: Context,
    action: ConfiguredAction.AppAction,
    onActionChanged: (ConfiguredAction.AppAction) -> Unit,
    onPickAppClicked: () -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        // Get app name from package name if available
        val appName = remember(action.packageName) {
            try {
                val pm = context.packageManager
                val appInfo = pm.getApplicationInfo(action.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                "Select app"
            }
        }

        Button(
            onClick = onPickAppClicked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (action.packageName.isBlank()) "Select App" else appName)
        }

        if (action.packageName.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))

            Text("Action Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    AppActionType.LAUNCH to "Launch App",
                    AppActionType.INFO to "App Info"
                ).forEach { (type, label) ->
                    FilterChip(
                        selected = action.actionType == type,
                        onClick = { onActionChanged(action.copy(actionType = type)) },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Notification action configuration UI.
 */
@Composable
internal fun NotificationActionConfig(
    action: ConfiguredAction.NotificationAction,
    onActionChanged: (ConfiguredAction.NotificationAction) -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        // Title
        OutlinedTextField(
            value = action.title,
            onValueChange = { newTitle ->
                onActionChanged(action.copy(title = newTitle))
            },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Text
        OutlinedTextField(
            value = action.text,
            onValueChange = { newText ->
                onActionChanged(action.copy(text = newText))
            },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Notification Type
        Text("Notification Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                NotificationType.NORMAL to "Normal",
                NotificationType.SILENT to "Silent",
                NotificationType.ONGOING to "Ongoing",
                NotificationType.REMINDER to "Reminder"
            ).forEach { (type, label) ->
                FilterChip(
                    selected = action.notificationType == type,
                    onClick = { onActionChanged(action.copy(notificationType = type)) },
                    label = { Text(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Delay (only for reminder type)
        if (action.notificationType == NotificationType.REMINDER) {
            Text("Delay: ${action.delayMinutes} minutes", color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = action.delayMinutes.toFloat(),
                onValueChange = { newValue ->
                    onActionChanged(action.copy(delayMinutes = newValue.toInt()))
                },
                valueRange = 0f..60f,
                steps = 12, // 5-minute increments
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Battery Saver action configuration UI.
 */
@Composable
internal fun BatterySaverActionConfig(
    action: ConfiguredAction.BatterySaver,
    onActionChanged: (ConfiguredAction.BatterySaver) -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (action.enabled) "Battery Saver: ON" else "Battery Saver: OFF",
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(
                checked = action.enabled,
                onCheckedChange = { newValue ->
                    onActionChanged(action.copy(enabled = newValue))
                }
            )
        }

        Text(
            "Note: Some manufacturers may restrict this setting",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}