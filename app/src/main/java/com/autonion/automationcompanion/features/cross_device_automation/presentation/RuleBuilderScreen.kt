package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationRule
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleAction
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleTrigger

@Composable
fun RuleBuilderScreen() {
    val context = LocalContext.current
    val manager = CrossDeviceAutomationManager.getInstance(context)
    val viewModel = viewModel { RuleBuilderViewModel(manager) }
    val rules by viewModel.rules.collectAsState()

    // Nested Scaffold is okay, but needs to ensure it doesn't double-pad if not careful.
    // However, since CrossDeviceAutomationScreen passes 'innerPadding' to the Surface wrapping this,
    // this Scaffold will start layout *below* the top bar and tabs.
    // We just need to make sure this Scaffold doesn't try to draw behind a non-existent status bar.
    
    var showDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Rule")
            }
        }
    ) { padding ->
        // Use the padding from this internal scaffold (for FAB)
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Active Rules", 
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            if (rules.isEmpty()) {
                Text(
                    "No rules configured. Tap + to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            LazyColumn {
                items(rules) { rule ->
                    RuleItem(rule, onDelete = { viewModel.deleteRule(rule.id) })
                }
            }
        }
    }
    
    if (showDialog) {
        AddRuleDialog(
            onDismiss = { showDialog = false },
            onSave = { name, trigger, action, params, target ->
                viewModel.addRule(
                    name,
                    trigger,
                    emptyList(),
                    listOf(RuleAction(action, params, if (target == "Local") null else target))
                )
                showDialog = false
            }
        )
    }
}

@Composable
fun AddRuleDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, Map<String, String>, String) -> Unit
) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("New Rule") }
    var selectedTrigger by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("clipboard.text_copied") }
    var selectedAction by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("open_url") }
    
    // Action Params
    var paramUrl by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var paramTitle by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Alert") }
    var paramMessage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Automation Triggered") }
    
    var selectedTarget by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Local") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Automation Rule") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Name") }
                )
                
                // Trigger Selection (Simplified Dropdown)
                Text("When:", modifier = Modifier.padding(top = 8.dp))
                DropdownSelection(
                    items = listOf("clipboard.text_copied", "system.battery.changed", "system.wifi.connected", "browser.navigation"),
                    selected = selectedTrigger,
                    onSelect = { selectedTrigger = it }
                )
                
                // Action Selection
                Text("Do:", modifier = Modifier.padding(top = 8.dp))
                DropdownSelection(
                    items = listOf("open_url", "send_notification", "enable_dnd"),
                    selected = selectedAction,
                    onSelect = { selectedAction = it }
                )
                
                // Params based on Action
                if (selectedAction == "open_url") {
                    androidx.compose.material3.OutlinedTextField(
                        value = paramUrl,
                        onValueChange = { paramUrl = it },
                        label = { Text("URL to Open") }
                    )
                } else if (selectedAction == "send_notification") {
                    androidx.compose.material3.OutlinedTextField(
                        value = paramTitle,
                        onValueChange = { paramTitle = it },
                        label = { Text("Notification Title") }
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = paramMessage,
                        onValueChange = { paramMessage = it },
                        label = { Text("Notification Message") }
                    )
                }
                
                // Target Selection
                Text("On Device:", modifier = Modifier.padding(top = 8.dp))
                // Hardcoded options + Local. In real app, fetch from ViewModel.
                DropdownSelection(
                    items = listOf("Local", "Remote (All)"), // TODO: Bind to actual connected devices
                    selected = selectedTarget,
                    onSelect = { selectedTarget = it }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val params = mutableMapOf<String, String>()
                if (selectedAction == "open_url") params["url"] = paramUrl
                if (selectedAction == "send_notification") {
                    params["title"] = paramTitle
                    params["message"] = paramMessage
                }
                onSave(name, selectedTrigger, selectedAction, params, selectedTarget)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DropdownSelection(items: List<String>, selected: String, onSelect: (String) -> Unit) {
    // Simple row of buttons for now to save complexity of Menu handling in this snippet
    androidx.compose.foundation.layout.Row(modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
        items.forEach { item ->
            androidx.compose.material3.FilterChip(
                selected = (item == selected),
                onClick = { onSelect(item) },
                label = { Text(item) },
                modifier = Modifier.padding(end = 4.dp)
            )
        }
    }
}

@Composable
fun RuleItem(rule: AutomationRule, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(rule.name, style = MaterialTheme.typography.titleMedium)
            Text("Trigger: ${rule.trigger.eventType}", style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}
