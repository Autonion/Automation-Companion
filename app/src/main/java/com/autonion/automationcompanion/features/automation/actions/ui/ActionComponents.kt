package com.autonion.automationcompanion.features.automation.actions.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction

/**
 * Reusable action row component for toggling any action on/off.
 * Trigger-agnostic: used by location, battery, app state, etc.
 */
@Composable
internal fun ActionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onCheckedChange, enabled = enabled)
    }
}

/**
 * Audio (Volume) action configuration UI.
 * Allows user to set ring and media volume levels.
 */
@Composable
internal fun AudioActionConfig(
    action: ConfiguredAction.Audio,
    onActionChanged: (ConfiguredAction.Audio) -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        Text("Ring: ${action.ringVolume}")
        Slider(
            value = action.ringVolume.toFloat(),
            onValueChange = { newValue ->
                onActionChanged(action.copy(ringVolume = newValue.toInt()))
            },
            valueRange = 0f..7f,
            modifier = Modifier.fillMaxWidth()
        )

        Text("Media: ${action.mediaVolume}")
        Slider(
            value = action.mediaVolume.toFloat(),
            onValueChange = { newValue ->
                onActionChanged(action.copy(mediaVolume = newValue.toInt()))
            },
            valueRange = 0f..15f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Brightness action configuration UI.
 */
@Composable
internal fun BrightnessActionConfig(
    action: ConfiguredAction.Brightness,
    onActionChanged: (ConfiguredAction.Brightness) -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        Text("Brightness: ${action.level}")
        Slider(
            value = action.level.toFloat(),
            onValueChange = { newValue ->
                onActionChanged(action.copy(level = newValue.toInt()))
            },
            valueRange = 10f..255f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Do Not Disturb action configuration UI.
 * Currently no additional config needed beyond the toggle.
 */
@Composable
internal fun DndActionConfig() {
    // DND has no additional configuration options
    // The toggle itself is sufficient
}

/**
 * Send SMS action configuration UI.
 * Requires user to provide message and select at least one contact.
 */
@Composable
internal fun SmsActionConfig(
    action: ConfiguredAction.SendSms,
    onActionChanged: (ConfiguredAction.SendSms) -> Unit,
    onPickContactClicked: () -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        OutlinedTextField(
            value = action.message,
            onValueChange = { newMessage ->
                onActionChanged(action.copy(message = newMessage))
            },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onPickContactClicked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select contact")
        }

        if (action.contactsCsv.isBlank()) {
            Text(
                "⚠ Select at least one contact",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                action.contactsCsv,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ───────────── Display Actions ─────────────


/**
 * Auto-rotate action configuration UI.
 * Non-root: Uses Settings.System ACCELEROMETER_ROTATION
 */
@Composable
internal fun AutoRotateActionConfig(
    action: ConfiguredAction.AutoRotate,
    onActionChanged: (ConfiguredAction.AutoRotate) -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (action.enabled) "Auto-rotate: ON" else "Auto-rotate: OFF",
                Modifier.weight(1f)
            )
            Switch(action.enabled, onCheckedChange = { newValue ->
                onActionChanged(action.copy(enabled = newValue))
            })
        }
    }
}

/**
 * Screen timeout action configuration UI.
 * Non-root: Uses Settings.System SCREEN_OFF_TIMEOUT
 * Predefined options: 15s, 30s, 1m, 5m
 */
@Composable
internal fun ScreenTimeoutActionConfig(
    action: ConfiguredAction.ScreenTimeout,
    onActionChanged: (ConfiguredAction.ScreenTimeout) -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        val options = listOf(
            15_000 to "15 seconds",
            30_000 to "30 seconds",
            60_000 to "1 minute",
            300_000 to "5 minutes"
        )

        Text("Screen Timeout", style = MaterialTheme.typography.labelMedium)
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = action.durationMs == value,
                    onClick = { onActionChanged(action.copy(durationMs = value)) }
                )
                Text(label, Modifier.padding(start = 8.dp))
            }
        }
    }
}


/**
 * Keep Screen Awake action configuration UI.
 * Non-root: Uses partial wake lock via foreground service.
 * Duration: acquired when trigger activates, released when deactivates.
 */
@Composable
internal fun KeepScreenAwakeActionConfig(
    action: ConfiguredAction.KeepScreenAwake,
    onActionChanged: (ConfiguredAction.KeepScreenAwake) -> Unit
) {
    Column(Modifier.padding(start = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (action.enabled) "Keep Screen Awake: ON" else "Keep Screen Awake: OFF",
                Modifier.weight(1f)
            )
            Switch(action.enabled, onCheckedChange = { newValue ->
                onActionChanged(action.copy(enabled = newValue))
            })
        }
        Text(
            "Active while this automation is triggered",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
