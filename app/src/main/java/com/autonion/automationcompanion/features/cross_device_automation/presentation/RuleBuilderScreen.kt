package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {
                // For demo, add a dummy rule
                viewModel.addRule(
                    "Demo Rule",
                    "clipboard.text_copied",
                    emptyList(),
                    listOf(RuleAction("enable_dnd"))
                )
            }) {
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
