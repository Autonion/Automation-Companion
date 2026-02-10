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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.autonion.automationcompanion.features.automation.actions.models.AutomationAction
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.automation.actions.builders.ActionBuilder
import java.time.DayOfWeek

@Composable
fun SlotConfigScreen(
    title: String,
    latitude: String,
    longitude: String,
    radiusMeters: Int,
    startLabel: String,
    endLabel: String,
    onLatitudeChanged: (String) -> Unit,
    onLongitudeChanged: (String) -> Unit,
    onRadiusChanged: (Int) -> Unit,
    onStartTimeClicked: () -> Unit,
    onEndTimeClicked: () -> Unit,
    onSaveClicked: (Int, List<AutomationAction>) -> Unit,
    onPickFromMapClicked: () -> Unit,
    onPickContactClicked: (actionIndex: Int) -> Unit,
    onPickAppClicked: (actionIndex: Int) -> Unit,  // NEW PARAMETER
    remindBeforeMinutes: String,
    onRemindBeforeMinutesChange: (String) -> Unit,
    selectedDays: Set<String>,
    onSelectedDaysChange: (Set<String>) -> Unit,
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    volumeEnabled: Boolean,  // To check if volume is on for DND disable logic
    context: android.content.Context  // NEW PARAMETER for app picker
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

        // ───────────── Actions (Delegated to ActionPicker) ─────────────
        // ActionPicker manages all action UI: SMS, Volume, Brightness, DND
        // This screen only handles trigger-specific config (location, time, days)
        ActionPicker(
            configuredActions = configuredActions,
            onActionsChanged = onActionsChanged,
            onPickContactClicked = onPickContactClicked,
            onPickAppClicked = onPickAppClicked,  // PASS NEW PARAMETER
            dndDisabledReason = if (volumeEnabled)
                "Disabled: Volume is active (Realme ROM conflict)" else null,
            context = context
        )

        Divider()

        // ───────────── Reminder ─────────────

        OutlinedTextField(
            value = remindBeforeMinutes,
            onValueChange = onRemindBeforeMinutesChange,
            label = { Text("Remind before (minutes)") },
            modifier = Modifier.fillMaxWidth()
        )

        // ───────────── Save ─────────────

        val hasAnyAction = ActionBuilder.hasAnyValidAction(configuredActions)

        Button(
            enabled = hasAnyAction,
            onClick = {
                val actions = ActionBuilder.buildActions(configuredActions)

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
