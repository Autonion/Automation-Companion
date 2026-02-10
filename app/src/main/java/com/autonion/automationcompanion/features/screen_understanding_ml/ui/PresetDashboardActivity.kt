package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Divider
import androidx.compose.material3.ListItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.autonion.automationcompanion.ui.theme.AppTheme


class PresetDashboardActivity : ComponentActivity() {

    private lateinit var repository: com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository
    
    @Composable
    fun NewAutomationDialog(
        onDismiss: () -> Unit,
        onConfirm: (String) -> Unit
    ) {
        var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("New Automation") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset name") },
                    singleLine = true
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = { onConfirm(name) },
                    enabled = name.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository(this)
        
        setContent {
            AppTheme {
                // Determine state
                val presets = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset>() }
                var showDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

                // Refresh on resume pattern
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            presets.clear()
                            presets.addAll(repository.getAllPresets())
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                if (showDialog) {
                    NewAutomationDialog(
                        onDismiss = { showDialog = false },
                        onConfirm = { name ->
                            showDialog = false
                            val intent = Intent(this@PresetDashboardActivity, SetupFlowActivity::class.java)
                            intent.putExtra("presetName", name)
                            startActivity(intent)
                        }
                    )
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Agent Automations") }
                        )
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = {
                                showDialog = true
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New Automation")
                        }
                    }
                ) { paddingValues ->
                    if (presets.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No automations yet. Tap + to create one.")
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        ) {
                            items(presets.size) { index ->
                                val preset = presets[index]
                                PresetItem(preset, onDelete = {
                                    repository.deletePreset(preset.id)
                                    // Refresh list
                                    presets.remove(preset)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Force refresh if needed (Compose LaunchedEffect might cover it if activity recreates, 
        // but for backstack navigation we need to trigger update. 
        // For simplicity, rely on recreation or add a trigger.)
        // Actually, since we use LaunchedEffect(Unit), it runs once per composition. 
        // To refresh on resume with Compose, we need a better state holder or a lifecycle observer.
        // Let's rely on standard restart for now, or the user can just re-open.
    }

    @Composable
    fun PresetItem(
        preset: com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset,
        onDelete: () -> Unit
    ) {
        androidx.compose.material3.ListItem(
            headlineContent = { Text(preset.name) },
            supportingContent = { Text("${preset.steps.size} steps • ${preset.scope}") },
            trailingContent = {
                androidx.compose.foundation.layout.Row {
                     androidx.compose.material3.IconButton(onClick = { 
                         // Check for confirmation? Or just delete for now.
                         onDelete()
                     }) {
                         Icon(
                             imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                             contentDescription = "Delete",
                             tint = androidx.compose.ui.graphics.Color.Red
                         )
                     }
                     androidx.compose.material3.IconButton(onClick = { 
                          val intent = Intent(this@PresetDashboardActivity, com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService::class.java).apply {
                                action = "PLAY_PRESET"
                                putExtra("presetId", preset.id)
                          }
                          if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                              startForegroundService(intent)
                          } else {
                              startService(intent)
                          }
                     }) {
                         Icon(
                             imageVector = androidx.compose.material.icons.Icons.Default.PlayArrow,
                             contentDescription = "Run"
                         )
                     }
                }
            },
            modifier = Modifier.clickable {
                  // Duplicate play
                  val intent = Intent(this@PresetDashboardActivity, com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService::class.java).apply {
                        action = "PLAY_PRESET"
                        putExtra("presetId", preset.id)
                  }
                  if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                      startForegroundService(intent)
                  } else {
                      startService(intent)
                  }
            }
        )
        androidx.compose.material3.Divider()
    }
}
