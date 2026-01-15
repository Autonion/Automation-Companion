package com.autonion.automationcompanion.features.system_context_automation.battery.ui

import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.automation.actions.builders.ActionBuilder
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryServiceManager
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig

class BatterySlotsActivity : AppCompatActivity() {

    private var contactPickerActionIndex: Int? = null

    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null && contactPickerActionIndex != null) {
            val cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val number = it.getString(0)
                    // This would require passing back to SlotConfigScreen
                    // For now, we'll handle it in the composable
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BatterySlotsScreen(
                    onBack = { finish() },
                    onAddClicked = { startBatteryConfig() }
                )
            }
        }
    }

    private fun startBatteryConfig() {
        startActivity(android.content.Intent(this, BatteryConfigActivity::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatterySlotsScreen(
    onBack: () -> Unit,
    onAddClicked: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).slotDao() }
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeleted by remember { mutableStateOf<Slot?>(null) }

    val allSlots by dao.getAllFlow().collectAsState(initial = emptyList())
    val slots = allSlots.filter { it.triggerType == "BATTERY" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Battery Automations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClicked) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        if (slots.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No battery automations yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(slots, key = { it.id }) { slot ->
                    BatterySlotCard(
                        slot = slot,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                dao.setEnabled(slot.id, enabled)
                                // Check if we need to start/stop monitoring
                                if (enabled) {
                                    BatteryServiceManager.startMonitoringIfNeeded(context)
                                } else {
                                    BatteryServiceManager.stopMonitoringIfNeeded(context)
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                dao.delete(slot)
                                recentlyDeleted = slot
                                // Check if we need to stop monitoring
                                BatteryServiceManager.stopMonitoringIfNeeded(context)

                                val result = snackbarHostState.showSnackbar(
                                    message = "Slot deleted",
                                    actionLabel = "Undo"
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    recentlyDeleted?.let {
                                        dao.insert(it.copy(id = 0))
                                        BatteryServiceManager.startMonitoringIfNeeded(context)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BatterySlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val config = try {
        slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.Battery>(it) }
    } catch (e: Exception) {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Battery ${config?.batteryPercentage}%",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        config?.thresholdType?.name ?: "Unknown",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Checkbox(
                    checked = slot.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDelete) {
                    Text("Delete")
                }
            }
        }
    }
}

// Compose wrapper for LazyColumn (needed for items)

