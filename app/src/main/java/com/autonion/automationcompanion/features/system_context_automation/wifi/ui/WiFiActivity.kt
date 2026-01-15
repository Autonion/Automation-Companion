package com.autonion.automationcompanion.features.system_context_automation.wifi.ui

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WiFiActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WiFiSlotsScreen(
                    onBack = { finish() },
                    onAddClicked = { startWiFiConfig() }
                )
            }
        }
    }

    private fun startWiFiConfig() {
        startActivity(android.content.Intent(this, WiFiConfigActivity::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiSlotsScreen(
    onBack: () -> Unit,
    onAddClicked: () -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).slotDao() }
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeleted by remember { mutableStateOf<Slot?>(null) }

    val allSlots by dao.getAllFlow().collectAsState(initial = emptyList())
    val slots = allSlots.filter { it.triggerType == "WIFI" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi Automations") },
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
                Text("No Wi-Fi automations yet")
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
                    WiFiSlotCard(
                        slot = slot,
                        onToggleEnabled = { enabled ->
                            scope.launch {
                                dao.setEnabled(slot.id, enabled)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                dao.delete(slot)
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
private fun WiFiSlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val config = try {
        slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.WiFi>(it) }
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
                        when (config?.connectionState) {
                            TriggerConfig.WiFi.ConnectionState.CONNECTED -> "Wi-Fi Connected"
                            TriggerConfig.WiFi.ConnectionState.DISCONNECTED -> "Wi-Fi Disconnected"
                            else -> "Unknown"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (config?.optionalSsid != null) {
                        Text(
                            "Network: ${config.optionalSsid}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
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

class WiFiConfigActivity : AppCompatActivity() {

    private var appPickerActionIndex = -1

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
        setContent {
            MaterialTheme {
                WiFiConfigScreen(
                    onBack = { finish() },
                    configuredActions = configuredActionsState,
                    onActionsChanged = { configuredActionsState = it },
                    onPickAppClicked = { actionIndex -> openAppPicker(actionIndex) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiConfigScreen(
    onBack: () -> Unit,
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickAppClicked: (Int) -> Unit
) {
    val context = LocalContext.current

    var connectionState by remember { mutableStateOf(TriggerConfig.WiFi.ConnectionState.CONNECTED) }
    var ssidFilter by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Wi-Fi Automation") },
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
            // Connection state selector
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Trigger", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = connectionState == TriggerConfig.WiFi.ConnectionState.CONNECTED,
                        onClick = { connectionState = TriggerConfig.WiFi.ConnectionState.CONNECTED },
                        label = { Text("Connected") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = connectionState == TriggerConfig.WiFi.ConnectionState.DISCONNECTED,
                        onClick = { connectionState = TriggerConfig.WiFi.ConnectionState.DISCONNECTED },
                        label = { Text("Disconnected") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Divider()

            // SSID filter (optional)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("SSID (Optional)", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = ssidFilter,
                    onValueChange = { ssidFilter = it },
                    label = { Text("Leave empty to match any network") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
                        // Handle contact picker if needed
                    },
                    onPickAppClicked = onPickAppClicked
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            Button(
                onClick = {
                    saveWiFiSlot(
                        context,
                        connectionState,
                        ssidFilter.ifBlank { null },
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

private fun saveWiFiSlot(
    context: Context,
    connectionState: TriggerConfig.WiFi.ConnectionState,
    ssid: String?,
    configuredActions: List<ConfiguredAction>,
    onSuccess: () -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val json = kotlinx.serialization.json.Json { classDiscriminator = "type" }

            val triggerConfig = TriggerConfig.WiFi(
                connectionState = connectionState,
                optionalSsid = ssid
            )

            val actions = ActionBuilder.buildActions(configuredActions)
            val triggerConfigJson = json.encodeToString(
                TriggerConfig.serializer(),
                triggerConfig as TriggerConfig
            )

            val slot = Slot(
                triggerType = "WIFI",
                triggerConfigJson = triggerConfigJson,
                actions = actions,
                enabled = true,
                activeDays = "ALL"
            )

            val dao = AppDatabase.get(context).slotDao()
            dao.insert(slot)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Wi-Fi automation created", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Error saving automation: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
