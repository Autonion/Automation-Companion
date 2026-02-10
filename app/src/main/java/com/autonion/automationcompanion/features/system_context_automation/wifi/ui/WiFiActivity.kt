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
import androidx.compose.foundation.clickable

class WiFiActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WiFiSlotsScreen(
                    onBack = { finish() },
                    onAddClicked = { startWiFiConfig() },
                    onEditClicked = { slotId -> startWiFiConfig(slotId) }
                )
            }
        }
    }

    private fun startWiFiConfig(slotId: Long = -1L) {
        val intent = android.content.Intent(this, WiFiConfigActivity::class.java)
        if (slotId != -1L) {
            intent.putExtra("slotId", slotId)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiSlotsScreen(
    onBack: () -> Unit,
    onAddClicked: () -> Unit,
    onEditClicked: (Long) -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).slotDao() }
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeleted by remember { mutableStateOf<Slot?>(null) }

    val slots by dao.getSlotsByType("WIFI").collectAsState(initial = emptyList())

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isLocationPermissionGranted by remember { mutableStateOf(true) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isLocationPermissionGranted = isGranted
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isLocationPermissionGranted = com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.isLocationPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
            FloatingActionButton(onClick = {
                if (!isLocationPermissionGranted) {
                    permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                } else if (!com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.isAccessibilityServiceEnabled(context)) {
                    android.widget.Toast.makeText(context, "Accessibility Service required for automation actions", android.widget.Toast.LENGTH_LONG).show()
                    com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.requestAccessibilityPermission(context)
                } else {
                    onAddClicked()
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (!isLocationPermissionGranted) {
                com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionWarningCard(
                    title = "Location Permission Required",
                    description = "Wi-Fi automation needs location permission to detect network SSID (Android requirement).",
                    buttonText = "Allow",
                    onClick = {
                        permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                )
            }

            if (slots.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No Wi-Fi automations yet")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(slots, key = { it.id }) { slot ->
                        WiFiSlotCard(
                            slot = slot,
                            onToggleEnabled = { enabled ->
                                if (enabled) {
                                    if (!com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.isLocationPermissionGranted(context)) {
                                         android.widget.Toast.makeText(context, "Location permission required", android.widget.Toast.LENGTH_LONG).show()
                                         permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                         return@WiFiSlotCard
                                    }
                                    if (!com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.isAccessibilityServiceEnabled(context)) {
                                         android.widget.Toast.makeText(context, "Accessibility Service required", android.widget.Toast.LENGTH_LONG).show()
                                         com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.requestAccessibilityPermission(context)
                                         return@WiFiSlotCard
                                    }
                                }
                                scope.launch {
                                    dao.setEnabled(slot.id, enabled)
                                }
                            },
                            onEdit = { onEditClicked(slot.id) },
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
}

@Composable
private fun WiFiSlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    val config = try {
        slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.WiFi>(it) }
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
           Toast.makeText(this, "Permission granted. Tap Save again.", Toast.LENGTH_SHORT).show()
        } else {
           Toast.makeText(this, "Location permission is required for Wi-Fi automation.", Toast.LENGTH_LONG).show()
        }
    }

    private var configuredActionsState by mutableStateOf<List<ConfiguredAction>>(emptyList())
    private var connectionState by mutableStateOf(TriggerConfig.WiFi.ConnectionState.CONNECTED)
    private var ssidFilter by mutableStateOf("")

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
                WiFiConfigScreen(
                    onBack = { finish() },
                    configuredActions = configuredActionsState,
                    connectionState = connectionState,
                    onConnectionStateChanged = { connectionState = it },
                    ssidFilter = ssidFilter,
                    onSsidFilterChanged = { ssidFilter = it },
                    onActionsChanged = { configuredActionsState = it },
                    onPickAppClicked = { actionIndex -> openAppPicker(actionIndex) },
                    onSaveClicked = { cState, ssid, actions ->
                         if (!com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.isLocationPermissionGranted(this)) {
                             permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                         } else {
                             saveWiFiSlot(this, slotId, cState, ssid, actions) {
                                 finish()
                             }
                         }
                    }
                )
            }
        }
    }

    private fun loadSlotData(id: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@WiFiConfigActivity).slotDao()
            val slot = dao.getById(id) ?: return@launch
            
            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val config = slot.triggerConfigJson?.let { 
                try { json.decodeFromString<TriggerConfig.WiFi>(it) } catch (e: Exception) { null } 
            }
            
            val loadedActions = ActionBuilder.toConfiguredActions(this@WiFiConfigActivity, slot.actions)
            
            CoroutineScope(Dispatchers.Main).launch {
                configuredActionsState = loadedActions
                config?.let {
                    connectionState = it.connectionState
                    ssidFilter = it.optionalSsid ?: ""
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WiFiConfigScreen(
    onBack: () -> Unit,
    configuredActions: List<ConfiguredAction>,
    connectionState: TriggerConfig.WiFi.ConnectionState,
    onConnectionStateChanged: (TriggerConfig.WiFi.ConnectionState) -> Unit,
    ssidFilter: String,
    onSsidFilterChanged: (String) -> Unit,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickAppClicked: (Int) -> Unit,
    onSaveClicked: (TriggerConfig.WiFi.ConnectionState, String?, List<ConfiguredAction>) -> Unit
) {
    val context = LocalContext.current

    // JIT Permissions Logic
    fun checkAndRequestWriteSettings(): Boolean {
        return if (android.provider.Settings.System.canWrite(context)) {
            true
        } else {
            val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = android.net.Uri.parse("package:${context.packageName}")
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Toast.makeText(context, "Grant 'Modify System Settings' permission to use this action.", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun checkAndRequestDndAccess(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return if (nm.isNotificationPolicyAccessGranted) {
            true
        } else {
             val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
             intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
             context.startActivity(intent)
             Toast.makeText(context, "Grant 'Do Not Disturb Access' permission to use this action.", Toast.LENGTH_LONG).show()
             false
        }
    }
    
    // Intercept action changes to check permissions
    val handleActionsChanged: (List<ConfiguredAction>) -> Unit = { newActions ->
        // Check if any NEW action requires permission
        val filtered = newActions.filter { action ->
            when (action) {
                is ConfiguredAction.Brightness -> checkAndRequestWriteSettings()
                is ConfiguredAction.Dnd -> checkAndRequestDndAccess()
                else -> true
            }
        }
        onActionsChanged(filtered)
    }

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
                        onClick = { onConnectionStateChanged(TriggerConfig.WiFi.ConnectionState.CONNECTED) },
                        label = { Text("Connected") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = connectionState == TriggerConfig.WiFi.ConnectionState.DISCONNECTED,
                        onClick = { onConnectionStateChanged(TriggerConfig.WiFi.ConnectionState.DISCONNECTED) },
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
                    onValueChange = onSsidFilterChanged,
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
                    onActionsChanged = handleActionsChanged,
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
                    onSaveClicked(
                        connectionState,
                        ssidFilter.ifBlank { null },
                        configuredActions
                    )
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
    slotId: Long,
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
                id = if (slotId != -1L) slotId else 0,
                triggerType = "WIFI",
                triggerConfigJson = triggerConfigJson,
                actions = actions,
                enabled = true,
                activeDays = "ALL"
            )

            val dao = AppDatabase.get(context).slotDao()
            if (slotId != -1L) {
                dao.update(slot)
            } else {
                dao.insert(slot)
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Wi-Fi automation saved", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Error saving automation: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
