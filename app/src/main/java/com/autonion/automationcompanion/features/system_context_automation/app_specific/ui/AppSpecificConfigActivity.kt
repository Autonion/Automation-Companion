package com.autonion.automationcompanion.features.system_context_automation.app_specific.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

class AppSpecificConfigActivity : AppCompatActivity() {

    private var appPickerActionIndex = -1
    private var isPickingTriggerApp = false

    // State for the trigger app (the app that triggers this automation)
    private var selectedTriggerAppPackage by mutableStateOf<String?>(null)
    private var selectedTriggerAppName by mutableStateOf<String?>(null)

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val packageName = result.data?.getStringExtra("selected_package_name")
            val appName = result.data?.getStringExtra("selected_app_name") // Assuming AppPicker returns name as well? verify.
            // Actually AppPickerActivity doesn't return app name currently, only package name in intent data "selected_package_name"
            // We might need to fetch the app name or just show package name for now/fetch it here.
            
            packageName?.let { pkg ->
                if (isPickingTriggerApp) {
                    selectedTriggerAppPackage = pkg
                    selectedTriggerAppName = pkg // Placeholder, ideally fetch label
                    isPickingTriggerApp = false
                } else {
                    updateAppAction(pkg)
                }
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
        isPickingTriggerApp = false
        val intent = Intent(this, AppPickerActivity::class.java)
        appPickerLauncher.launch(intent)
    }

    private fun openTriggerAppPicker() {
        isPickingTriggerApp = true
        val intent = Intent(this, AppPickerActivity::class.java)
        appPickerLauncher.launch(intent)
    }

    private var slotId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        slotId = intent.getLongExtra("slotId", -1L)
        if (slotId != -1L) {
            loadSlotData(slotId)
        }

        setContent {
            MaterialTheme {
                AppSpecificConfigScreen(
                    onBack = { finish() },
                    configuredActions = configuredActionsState,
                    onActionsChanged = { configuredActionsState = it },
                    onPickAppClicked = { actionIndex -> openAppPicker(actionIndex) },
                    onPickTriggerApp = { openTriggerAppPicker() },
                    selectedTriggerApp = selectedTriggerAppPackage,
                    onSave = {
                        saveAppSlot(this, slotId, selectedTriggerAppPackage, configuredActionsState) {
                            finish()
                        }
                    }
                )
            }
        }
    }

    private fun loadSlotData(id: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@AppSpecificConfigActivity).slotDao()
            val slot = dao.getById(id) ?: return@launch

            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val config = slot.triggerConfigJson?.let {
                try { json.decodeFromString<TriggerConfig.App>(it) } catch (e: Exception) { null }
            }

            val loadedActions = ActionBuilder.toConfiguredActions(this@AppSpecificConfigActivity, slot.actions)

            CoroutineScope(Dispatchers.Main).launch {
                configuredActionsState = loadedActions
                config?.let {
                    selectedTriggerAppPackage = it.packageName
                    selectedTriggerAppName = it.appName ?: it.packageName
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSpecificConfigScreen(
    onBack: () -> Unit,
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickAppClicked: (Int) -> Unit,
    onPickTriggerApp: () -> Unit,
    selectedTriggerApp: String?,
    onSave: () -> Unit
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
                title = { Text("App Specific Automation") },
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
            // Trigger App Selector
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Trigger App", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedButton(
                    onClick = onPickTriggerApp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedTriggerApp ?: "Select App")
                }
                Text("Automation triggers when this app is opened", style = MaterialTheme.typography.bodySmall)
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
                    onPickContactClicked = { _ -> },
                    onPickAppClicked = onPickAppClicked
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Save button
            Button(
                onClick = onSave,
                enabled = selectedTriggerApp != null,
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

private fun saveAppSlot(
    context: Context,
    slotId: Long,
    packageName: String?,
    configuredActions: List<ConfiguredAction>,
    onSuccess: () -> Unit
) {
    if (packageName == null) return

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val json = kotlinx.serialization.json.Json { classDiscriminator = "type" }

            val triggerConfig = TriggerConfig.App(
                packageName = packageName,
                appName = packageName // Ideally we resolve the name
            )

            val actions = ActionBuilder.buildActions(configuredActions)
            val triggerConfigJson = json.encodeToString(
                TriggerConfig.serializer(),
                triggerConfig as TriggerConfig
            )

            val slot = Slot(
                id = if (slotId != -1L) slotId else 0,
                triggerType = "APP",
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
                Toast.makeText(context, "Automation saved", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Error saving: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
