package com.autonion.automationcompanion.features.system_context_automation.timeofday.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.autonion.automationcompanion.features.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.features.system_context_automation.timeofday.engine.TimeOfDayReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable

class TimeOfDayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TimeOfDaySlotsScreen(
                    onBack = { finish() },
                    onAddClicked = { startTimeOfDayConfig() },
                    onEditClicked = { slotId -> startTimeOfDayConfig(slotId) }
                )
            }
        }
    }

    private fun startTimeOfDayConfig(slotId: Long = -1L) {
        val intent = android.content.Intent(this, TimeOfDayConfigActivity::class.java)
        if (slotId != -1L) {
            intent.putExtra("slotId", slotId)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOfDaySlotsScreen(
    onBack: () -> Unit,
    onAddClicked: () -> Unit,
    onEditClicked: (Long) -> Unit
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
                        onEdit = { onEditClicked(slot.id) },
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
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val config = try {
        slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.TimeOfDay>(it) }
    } catch (e: Exception) {
        null
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
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

    private var appPickerActionIndex = -1
    private var slotId: Long = -1L

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val packageName = result.data?.getStringExtra("selected_package_name")
            packageName?.let { pkg ->
                updateAppAction(pkg)
            }
        }
    }

    private var configuredActionsState by mutableStateOf<List<ConfiguredAction>>(emptyList())

    // State for the config values
    private var loadedHour by mutableIntStateOf(8)
    private var loadedMinute by mutableIntStateOf(0)
    private var loadedRepeatDaily by mutableStateOf(true)

    private fun updateAppAction(packageName: String) {
        if (appPickerActionIndex >= 0 && appPickerActionIndex < configuredActionsState.size) {
            val appAction = configuredActionsState.getOrNull(appPickerActionIndex)
            if (appAction is ConfiguredAction.AppAction) {
                configuredActionsState = configuredActionsState.mapIndexed { idx, action ->
                    if (idx == appPickerActionIndex) {
                        appAction.copy(packageName = packageName)
                    } else {
                        action
                    }
                }
                appPickerActionIndex = -1
            }
        }
    }

    private fun openAppPicker(actionIndex: Int) {
        appPickerActionIndex = actionIndex
        val intent = Intent(this, AppPickerActivity::class.java)
        appPickerLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        slotId = intent.getLongExtra("slotId", -1L)
        if (slotId != -1L) {
            loadSlotData(slotId)
        }

        setContent {
            MaterialTheme {
                // Pass loaded values as initial values if we have them, creating a key to reset state when they change
                // But Composable state initialization happens once.
                // We need to pass the state down or initialize the composable with them.
                // Better: Hoist the state to Activity or use a ViewModel. 
                // Given the current structure, I'll pass the state objects to the screen.
                // Or I can just pass the values and let the screen initialize its state, 
                // BUT if I update the values asynchronously (from DB), the screen state won't update unless I key it or pass mutable state.
                
                // Let's refactor ConfigScreen to accept initial values
                // Since I can't easily refactor the whole screen signature without changing its internal state handling,
                // I will use a key to force recomposition when data loads. Or I can pass the state objects.
                
                // Keying by slotId might not be enough if data loads later.
                // Let's make the screen state observable from here.
                
                TimeOfDayConfigScreen(
                    onBack = { finish() },
                    configuredActions = configuredActionsState,
                    onActionsChanged = { configuredActionsState = it },
                    onPickAppClicked = { actionIndex -> openAppPicker(actionIndex) },
                    initialHour = loadedHour,
                    initialMinute = loadedMinute,
                    initialRepeatDaily = loadedRepeatDaily,
                    isEditing = slotId != -1L,
                    onSave = { h, m, r, actions ->
                         saveTimeOfDaySlot(this, slotId, h, m, r, actions) {
                             finish()
                         }
                    }
                )
            }
        }
    }

    private fun loadSlotData(id: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@TimeOfDayConfigActivity).slotDao()
            val slot = dao.getById(id) ?: return@launch

            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
             val config = slot.triggerConfigJson?.let {
                try { json.decodeFromString<TriggerConfig.TimeOfDay>(it) } catch (e: Exception) { null }
            }

            val loadedActions = ActionBuilder.toConfiguredActions(this@TimeOfDayConfigActivity, slot.actions)

            CoroutineScope(Dispatchers.Main).launch {
                configuredActionsState = loadedActions
                config?.let {
                    loadedHour = it.hour
                    loadedMinute = it.minute
                    loadedRepeatDaily = it.repeatDaily
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeOfDayConfigScreen(
    onBack: () -> Unit,
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickAppClicked: (Int) -> Unit,
    initialHour: Int = 8,
    initialMinute: Int = 0,
    initialRepeatDaily: Boolean = true,
    isEditing: Boolean = false,
    onSave: (Int, Int, Boolean, List<ConfiguredAction>) -> Unit = { _, _, _, _ -> } // Modified signature
) {
    val context = LocalContext.current

    // Use current values if they change (from loading)
    var hour by remember(initialHour) { mutableIntStateOf(initialHour) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute) }
    var repeatDaily by remember(initialRepeatDaily) { mutableStateOf(initialRepeatDaily) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Time-of-Day Automation" else "Create Time-of-Day Automation") },
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
                    onActionsChanged = onActionsChanged,
                    onPickContactClicked = { _ ->
                        // Handle contact picker
                    },
                    onPickAppClicked = onPickAppClicked
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            Button(
                onClick = {
                    onSave(hour, minute, repeatDaily, configuredActions)
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
    slotId: Long,
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
                id = if (slotId != -1L) slotId else 0,
                triggerType = "TIME_OF_DAY",
                triggerConfigJson = triggerConfigJson,
                actions = actions,
                enabled = true,
                activeDays = "ALL"
            )

            val dao = AppDatabase.get(context).slotDao()
            val finalSlotId = if (slotId != -1L) {
                dao.update(slot)
                slotId
            } else {
                dao.insert(slot)
            }

            // Schedule alarm
            TimeOfDayReceiver.scheduleAlarm(context, finalSlotId, hour, minute)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Time-of-day automation saved", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Error saving automation: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
