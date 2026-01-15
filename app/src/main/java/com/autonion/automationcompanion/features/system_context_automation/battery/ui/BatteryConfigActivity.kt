package com.autonion.automationcompanion.features.system_context_automation.battery.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.automation.actions.builders.ActionBuilder
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryServiceManager
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class BatteryConfigActivity : AppCompatActivity() {

    private var contactPickerActionIndex: Int? = null
    private var appPickerActionIndex = -1

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
                    // Update would go here
                }
            }
        }
    }

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
                BatteryConfigScreen(
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
fun BatteryConfigScreen(
    onBack: () -> Unit,
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickAppClicked: (Int) -> Unit
) {
    val context = LocalContext.current

    var batteryPercentage by remember { mutableIntStateOf(20) }
    var thresholdType by remember { mutableStateOf(TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW) }
    var contactPickerActionIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Battery Automation") },
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
            // Battery percentage selector
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Battery Percentage", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = batteryPercentage.toFloat(),
                    onValueChange = { batteryPercentage = it.toInt() },
                    valueRange = 1f..100f,
                    steps = 99,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("$batteryPercentage%", style = MaterialTheme.typography.bodyLarge)
            }

            Divider()

            // Threshold type selector
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Trigger Type", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = thresholdType == TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW,
                        onClick = { thresholdType = TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW },
                        label = { Text("At or Below") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = thresholdType == TriggerConfig.Battery.ThresholdType.REACHES_OR_ABOVE,
                        onClick = { thresholdType = TriggerConfig.Battery.ThresholdType.REACHES_OR_ABOVE },
                        label = { Text("At or Above") },
                        modifier = Modifier.weight(1f)
                    )
                }
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
                    onPickContactClicked = { actionIndex ->
                        contactPickerActionIndex = actionIndex
                        // Launch contact picker
                    },
                    onPickAppClicked = onPickAppClicked
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            Button(
                onClick = {
                    saveBatterySlot(
                        context,
                        batteryPercentage,
                        thresholdType,
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

private fun saveBatterySlot(
    context: Context,
    batteryPercentage: Int,
    thresholdType: TriggerConfig.Battery.ThresholdType,
    configuredActions: List<ConfiguredAction>,
    onSuccess: () -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val json = Json { classDiscriminator = "type" }

            val triggerConfig = TriggerConfig.Battery(
                batteryPercentage = batteryPercentage,
                thresholdType = thresholdType
            )

            val actions = ActionBuilder.buildActions(configuredActions)
            val triggerConfigJson = json.encodeToString(
                TriggerConfig.serializer(),
                triggerConfig as TriggerConfig
            )

            val slot = Slot(
                triggerType = "BATTERY",
                triggerConfigJson = triggerConfigJson,
                actions = actions,
                enabled = true,
                activeDays = "ALL"
            )

            val dao = AppDatabase.get(context).slotDao()
            dao.insert(slot)

            // Start battery monitoring service
            BatteryServiceManager.startMonitoringIfNeeded(context)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Battery automation created", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Error saving automation: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
