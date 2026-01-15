package com.autonion.automationcompanion.features.system_context_automation.timeofday.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.automation.actions.builders.ActionBuilder
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.features.system_context_automation.timeofday.engine.TimeOfDayReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TimeOfDayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TimeOfDaySlotsScreen(
                    onBack = { finish() },
                    onAddClicked = { startTimeOfDayConfig() }
                )
            }
        }
    }

    private fun startTimeOfDayConfig() {
        startActivity(android.content.Intent(this, TimeOfDayConfigActivity::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOfDaySlotsScreen(
    onBack: () -> Unit,
    onAddClicked: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).slotDao() }
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeleted by remember { mutableStateOf<Slot?>(null) }

    val allSlots by dao.getAllFlow().collectAsState(initial = emptyList())
    val slots = allSlots.filter { it.triggerType == "TIME_OF_DAY" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time-of-Day Automations") },
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
                Icon(Icons.Default.Add, contentDescription = "Add")
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
                Text("No time-of-day automations yet")
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
                    TimeOfDaySlotCard(
                        slot = slot,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                dao.setEnabled(slot.id, enabled)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                dao.delete(slot)
                                TimeOfDayReceiver.cancelAlarm(context, slot.id)
                                recentlyDeleted = slot
                                val result = snackbarHostState.showSnackbar(
                                    message = "Slot deleted",
                                    actionLabel = "Undo"
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    recentlyDeleted?.let { dao.insert(it.copy(id = 0)) }
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
private fun TimeOfDaySlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val config = try {
        slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.TimeOfDay>(it) }
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
                        String.format("%02d:%02d", config?.hour ?: 0, config?.minute ?: 0),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (config?.repeatDaily == true) "Daily" else "Once",
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

class TimeOfDayConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TimeOfDayConfigScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOfDayConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var hour by remember { mutableIntStateOf(8) }
    var minute by remember { mutableIntStateOf(0) }
    var repeatDaily by remember { mutableStateOf(true) }
    var configuredActions by remember { mutableStateOf<List<ConfiguredAction>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Time-of-Day Automation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Time picker
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Time", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hour
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { if (hour > 0) hour-- else hour = 23 }) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                        }
                        Text(String.format("%02d", hour), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { if (hour < 23) hour++ else hour = 0 }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                    }

                    Text(":", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))

                    // Minute
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { if (minute >= 15) minute -= 15 else minute = 45 }) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                        }
                        Text(String.format("%02d", minute), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { if (minute < 45) minute += 15 else minute = 0 }) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                        }
                    }
                }
            }

            Divider()

            // Repeat toggle
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Repeat Daily", style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = repeatDaily,
                    onCheckedChange = { repeatDaily = it }
                )
            }

            Divider()

            // Action picker
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Actions", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                ActionPicker(
                    context = context,
                    configuredActions = configuredActions,
                    onActionsChanged = { configuredActions = it },
                    onPickContactClicked = { _ ->
                        // Handle contact picker
                    },
                    onPickAppClicked = { }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            Button(
                onClick = {
                    saveTimeOfDaySlot(
                        context,
                        hour,
                        minute,
                        repeatDaily,
                        configuredActions
                    ) {
                        onBack()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("Save Automation")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun saveTimeOfDaySlot(
    context: Context,
    hour: Int,
    minute: Int,
    repeatDaily: Boolean,
    configuredActions: List<ConfiguredAction>,
    onSuccess: () -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val json = kotlinx.serialization.json.Json { classDiscriminator = "type" }

            val triggerConfig = TriggerConfig.TimeOfDay(
                hour = hour,
                minute = minute,
                repeatDaily = repeatDaily,
                activeDays = "ALL"
            )

            val actions = ActionBuilder.buildActions(configuredActions)
            val triggerConfigJson = json.encodeToString(
                TriggerConfig.serializer(),
                triggerConfig as TriggerConfig
            )

            val slot = Slot(
                triggerType = "TIME_OF_DAY",
                triggerConfigJson = triggerConfigJson,
                actions = actions,
                enabled = true,
                activeDays = "ALL"
            )

            val dao = AppDatabase.get(context).slotDao()
            val slotId = dao.insert(slot)

            // Schedule alarm
            TimeOfDayReceiver.scheduleAlarm(context, slotId, hour, minute)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Time-of-day automation created", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Error saving automation: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
