package com.autonion.automationcompanion.features.system_context_automation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Intent
import com.autonion.automationcompanion.features.system_context_automation.location.LocationSlotsActivity
import com.autonion.automationcompanion.features.system_context_automation.battery.ui.BatterySlotsActivity
import com.autonion.automationcompanion.features.system_context_automation.timeofday.ui.TimeOfDayActivity
import com.autonion.automationcompanion.features.system_context_automation.wifi.ui.WiFiActivity


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemContextMainScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showTriggerDialog by remember { mutableStateOf(false) }

    if (showTriggerDialog) {
        TriggerSelectionDialog(
            onDismiss = { showTriggerDialog = false },
            onLocationSelected = {
                showTriggerDialog = false
                context.startActivity(Intent(context, LocationSlotsActivity::class.java))
            },
            onBatterySelected = {
                showTriggerDialog = false
                context.startActivity(Intent(context, BatterySlotsActivity::class.java))
            },
            onTimeOfDaySelected = {
                showTriggerDialog = false
                context.startActivity(Intent(context, TimeOfDayActivity::class.java))
            },
            onWiFiSelected = {
                showTriggerDialog = false
                context.startActivity(Intent(context, WiFiActivity::class.java))
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System Context Automation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showTriggerDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Automation")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Automations",
                style = MaterialTheme.typography.titleMedium
            )

            // All trigger types can be accessed via FAB
            Text(
                text = "Tap the '+' button to add a new automation trigger",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quick reference cards
            FeatureCard(
                title = "Location-Based",
                description = "Trigger on geofence entry/exit",
                onClick = { /* Info only */ }
            )

            FeatureCard(
                title = "Battery Level",
                description = "Trigger when battery reaches threshold",
                onClick = { /* Info only */ }
            )

            FeatureCard(
                title = "Time of Day",
                description = "Trigger at specific time daily",
                onClick = { /* Info only */ }
            )

            FeatureCard(
                title = "Wi-Fi Connectivity",
                description = "Trigger on Wi-Fi connect/disconnect",
                onClick = { /* Info only */ }
            )
        }
    }
}

@Composable
private fun TriggerSelectionDialog(
    onDismiss: () -> Unit,
    onLocationSelected: () -> Unit,
    onBatterySelected: () -> Unit,
    onTimeOfDaySelected: () -> Unit,
    onWiFiSelected: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Trigger Type") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TriggerOption(
                    label = "Location",
                    description = "Geofence-based trigger",
                    onClick = onLocationSelected
                )
                TriggerOption(
                    label = "Battery",
                    description = "Battery threshold trigger",
                    onClick = onBatterySelected
                )
                TriggerOption(
                    label = "Time of Day",
                    description = "Scheduled time trigger",
                    onClick = onTimeOfDaySelected
                )
                TriggerOption(
                    label = "Wi-Fi",
                    description = "Wi-Fi connectivity trigger",
                    onClick = onWiFiSelected
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun TriggerOption(
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
//fun isAccessibilityEnabled(context: Context): Boolean {
//    val am = Settings.Secure.getInt(
//        context.contentResolver,
//        Settings.Secure.ACCESSIBILITY_ENABLED, 0
//    )
//    return am == 1
//}

//fun openAccessibilitySettings(context: Context) {
//    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
//    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//    context.startActivity(intent)
//}

@Composable
private fun FeatureCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TodoItem(label: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("• $label", style = MaterialTheme.typography.bodyMedium)
    }
}
