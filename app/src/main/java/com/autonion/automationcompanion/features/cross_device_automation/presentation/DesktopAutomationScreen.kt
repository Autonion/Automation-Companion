package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.autonion.automationcompanion.features.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationRule
import android.content.Intent

@Composable
fun DesktopAutomationScreen() {
    val context = LocalContext.current
    val manager = CrossDeviceAutomationManager.getInstance(context)
    val viewModel = viewModel { DesktopAutomationViewModel(manager) }
    val rules by viewModel.rules.collectAsState()
    
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Rule")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (rules.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No Desktop Rules defined.\nTap + to create one (e.g., 'Meeting -> DND')",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rules) { rule ->
                        DesktopRuleItem(rule, onDelete = { viewModel.deleteRule(rule.id) })
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateDesktopRuleDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, category, url, actions ->
                viewModel.createRule(name, category, url, actions)
                showCreateDialog = false
            }
        )
    }
}

@Composable
fun DesktopRuleItem(rule: AutomationRule, onDelete: () -> Unit) {
    Card(
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                val conditionText = rule.conditions.firstOrNull()?.let { 
                    if (it is com.autonion.automationcompanion.features.cross_device_automation.domain.RuleCondition.PayloadContains) {
                         if (it.key == "url") "URL: ${it.value}" else "Type: ${it.value}"
                    } else "Unknown Condition"
                } ?: "No Condition"
                Text(conditionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text("${rule.actions.size} Actions", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun CreateDesktopRuleDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, category: String, url: String?, actions: List<ConfiguredAction>) -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(1) }
    var name by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Meeting") }
    var customUrl by remember { mutableStateOf("") }
    var actions by remember { mutableStateOf<List<ConfiguredAction>>(emptyList()) }

    var pendingAppActionIndex by remember { mutableStateOf(-1) }

    val appPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val packageName = result.data?.getStringExtra("selected_package_name")
            if (packageName != null && pendingAppActionIndex >= 0 && pendingAppActionIndex < actions.size) {
                 val currentAction = actions[pendingAppActionIndex]
                 if (currentAction is ConfiguredAction.AppAction) {
                     val updatedAction = currentAction.copy(packageName = packageName)
                     val newActions = actions.toMutableList()
                     newActions[pendingAppActionIndex] = updatedAction
                     actions = newActions
                 }
            }
        }
        pendingAppActionIndex = -1
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(600.dp), // Fixed height for complex content
        title = { Text(if (step == 1) "When..." else "Then...") },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                if (step == 1) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Rule Name (e.g. Work Mode)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text("Trigger Condition", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val categories = listOf("Meeting", "Social", "Work", "Custom URL")
                    var expanded by remember { mutableStateOf(false) }
                    
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedCategory)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat) },
                                    onClick = { 
                                        selectedCategory = cat
                                        expanded = false 
                                    }
                                )
                            }
                        }
                    }

                    if (selectedCategory == "Custom URL") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("URL Contains (e.g. youtube.com)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    // Step 2: Actions
                    // We need a scrollable container because ActionPicker is tall
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.lazy.LazyColumn {
                            item {
                                ActionPicker(
                                    configuredActions = actions,
                                    onActionsChanged = { actions = it },
                                    onPickContactClicked = { /* TODO: Contact Picker */ },
                                    onPickAppClicked = { index ->
                                        pendingAppActionIndex = index
                                        val intent = Intent(context, AppPickerActivity::class.java)
                                        appPickerLauncher.launch(intent)
                                    },
                                    context = context
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (step == 1) {
                        if (name.isNotBlank()) step = 2
                    } else {
                         onCreate(name, selectedCategory, customUrl, actions)
                    }
                },
                enabled = (step == 1 && name.isNotBlank()) || (step == 2 && actions.isNotEmpty())
            ) {
                Text(if (step == 1) "Next" else "Create Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (step == 2) step = 1 else onDismiss()
            }) {
                Text(if (step == 2) "Back" else "Cancel")
            }
        }
    )
}
