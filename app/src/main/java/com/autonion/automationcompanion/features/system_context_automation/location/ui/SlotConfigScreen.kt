package com.autonion.automationcompanion.features.system_context_automation.location.ui

import android.widget.SeekBar
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.autonion.automationcompanion.features.system_context_automation.location.helpers.AutomationAction
import java.time.DayOfWeek

@Composable
fun SlotConfigScreen(
    title: String,
    latitude: String,
    longitude: String,
    radiusMeters: Int,
    message: String,
    contactsCsv: String,
    startLabel: String,
    endLabel: String,
    onLatitudeChanged: (String) -> Unit,
    onLongitudeChanged: (String) -> Unit,
    onRadiusChanged: (Int) -> Unit,
    onMessageChanged: (String) -> Unit,
    onPickContactClicked: () -> Unit,
    onStartTimeClicked: () -> Unit,
    onEndTimeClicked: () -> Unit,
    onSaveClicked: (Int, List<AutomationAction>) -> Unit,
    onPickFromMapClicked: () -> Unit,
    smsEnabled: Boolean,
    onSmsEnabledChange: (Boolean) -> Unit,

    volumeEnabled: Boolean,
    ringVolume: Float,
    mediaVolume: Float,
    onVolumeEnabledChange: (Boolean) -> Unit,
    onRingVolumeChange: (Float) -> Unit,
    onMediaVolumeChange: (Float) -> Unit,

    brightnessEnabled: Boolean,
    brightness: Float,
    onBrightnessEnabledChange: (Boolean) -> Unit,
    onBrightnessChange: (Float) -> Unit,

    dndEnabled: Boolean,
    onDndEnabledChange: (Boolean) -> Unit,
    remindBeforeMinutes: String,
    onRemindBeforeMinutesChange: (String) -> Unit,

    selectedDays: Set<String>,
    onSelectedDaysChange: (Set<String>) -> Unit,

    ) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()


    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        Text(title, style = MaterialTheme.typography.headlineSmall)


        // ───────────── Location ─────────────

        OutlinedTextField(
            value = latitude,
            onValueChange = onLatitudeChanged,
            label = { Text("Latitude") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = longitude,
            onValueChange = onLongitudeChanged,
            label = { Text("Longitude") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = onPickFromMapClicked, modifier = Modifier.fillMaxWidth()) {
            Text("Pick from map")
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Start time")
                Text(startLabel, modifier = Modifier.clickable { onStartTimeClicked() })
            }
            Column {
                Text("End time")
                Text(endLabel, modifier = Modifier.clickable { onEndTimeClicked() })
            }
        }

        Divider()
        Text("Active Days", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("MON","TUE","WED","THU","FRI","SAT","SUN").forEach { day ->
                FilterChip(
                    selected = selectedDays.contains(day),
                    onClick = {
                        onSelectedDaysChange(
                            if (selectedDays.contains(day))
                                selectedDays - day
                            else
                                selectedDays + day
                        )
                    },
                    label = { Text(day) }
                )
            }
        }

        if (selectedDays.isEmpty()) {
            Text(
                "⚠ Select at least one day",
                color = MaterialTheme.colorScheme.error
            )
        }




        // ───────────── Radius ─────────────

        Text("Radius: $radiusMeters m")

        AndroidView(
            factory = { ctx ->
                SeekBar(ctx).apply {
                    max = 2000
                    progress = radiusMeters
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, f: Boolean) {
                            onRadiusChanged(maxOf(50, p))
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Divider()
        Text("Automations", style = MaterialTheme.typography.titleMedium)

        // ───────────── SMS ─────────────

        ActionRow(
            label = "Send Message",
            checked = smsEnabled,
            onCheckedChange = onSmsEnabledChange,
            onTest = {
                Toast.makeText(
                    ctx,
                    if (contactsCsv.isBlank())
                        "No contacts selected"
                    else
                        "Test: \"$message\" → $contactsCsv",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        if (smsEnabled) {
            Column(Modifier.padding(start = 12.dp)) {

                OutlinedTextField(
                    value = message,
                    onValueChange = onMessageChanged,
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = onPickContactClicked,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Select contact") }

                if (contactsCsv.isBlank()) {
                    Text(
                        "⚠ Select at least one contact",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(contactsCsv, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ───────────── Volume ─────────────

        ActionRow(
            label = "Set Volume",
            checked = volumeEnabled,
            onCheckedChange = onVolumeEnabledChange,
            onTest = {
                Toast.makeText(
                    ctx,
                    "Test volume → Ring ${ringVolume.toInt()}, Media ${mediaVolume.toInt()}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        if (volumeEnabled) {
            Column(Modifier.padding(start = 12.dp)) {
                Text("Ring: ${ringVolume.toInt()}")
                Slider(
                    value = ringVolume,
                    onValueChange = onRingVolumeChange,
                    valueRange = 0f..15f
                )

                Text("Media: ${mediaVolume.toInt()}")
                Slider(
                    value = mediaVolume,
                    onValueChange = onMediaVolumeChange,
                    valueRange = 0f..15f
                )

            }
        }

        // ───────────── Brightness ─────────────

        ActionRow(
            label = "Set Brightness",
            checked = brightnessEnabled,
            onCheckedChange = onBrightnessEnabledChange,
            onTest = {
                Toast.makeText(
                    ctx,
                    "Test brightness → ${brightness.toInt()}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        if (brightnessEnabled) {
            Column(Modifier.padding(start = 12.dp)) {
                Text("Brightness: ${brightness.toInt()}")
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = 10f..255f
                )

            }
        }

        // ───────────── DND ─────────────

        ActionRow(
            label = "Do Not Disturb",
            checked = dndEnabled,
            onCheckedChange = onDndEnabledChange,
            onTest = {
                Toast.makeText(ctx, "Test DND toggle", Toast.LENGTH_SHORT).show()
            }
        )

        // ───────────── Reminder ─────────────

        OutlinedTextField(
            value = remindBeforeMinutes,
            onValueChange = onRemindBeforeMinutesChange,
            label = { Text("Remind before (minutes)") },
            modifier = Modifier.fillMaxWidth()
        )

        // ───────────── Save ─────────────

        val hasAnyAction =
            smsEnabled || volumeEnabled || brightnessEnabled || dndEnabled

        Button(
            enabled = hasAnyAction,
            onClick = {
                val actions = buildActions(
                    message,
                    contactsCsv,
                    smsEnabled,
                    volumeEnabled,
                    ringVolume,
                    mediaVolume,
                    brightnessEnabled,
                    brightness,
                    dndEnabled
                )

                onSaveClicked(
                    remindBeforeMinutes.toIntOrNull() ?: 15,
                    actions
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Slot")
        }

        if (!hasAnyAction) {
            Text(
                "⚠ Enable at least one automation",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onTest: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = onTest) { Text("Test") }
        Switch(checked, onCheckedChange)
    }
}

private fun buildActions(
    message: String,
    contactsCsv: String,
    smsEnabled: Boolean,
    volumeEnabled: Boolean,
    ringVolume: Float,
    mediaVolume: Float,
    brightnessEnabled: Boolean,
    brightness: Float,
    dndEnabled: Boolean
): List<AutomationAction> {

    val actions = mutableListOf<AutomationAction>()

    if (smsEnabled && contactsCsv.isNotBlank()) {
        actions += AutomationAction.SendSms(message, contactsCsv)
    }
    if (volumeEnabled) {
        actions += AutomationAction.SetVolume(
            ringVolume.toInt(),
            mediaVolume.toInt()
        )
    }
    if (brightnessEnabled) {
        actions += AutomationAction.SetBrightness(brightness.toInt())
    }
    if (dndEnabled) {
        actions += AutomationAction.SetDnd(true)
    }

    return actions
}