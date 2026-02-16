package com.autonion.automationcompanion.features.system_context_automation.app_specific.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.automation.actions.builders.ActionBuilder
import com.autonion.automationcompanion.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ── Accent Colors ──
private val AppAccent = Color(0xFF4CAF50)     // Green
private val ActionsAccent = Color(0xFFFF6D00) // Orange

class AppSpecificConfigActivity : AppCompatActivity() {

    private var appPickerActionIndex = -1
    private var isPickingTriggerApp = false

    private var selectedTriggerAppPackage by mutableStateOf<String?>(null)
    private var selectedTriggerAppName by mutableStateOf<String?>(null)

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val packageName = result.data?.getStringExtra("selected_package_name")
            packageName?.let { pkg ->
                if (isPickingTriggerApp) {
                    selectedTriggerAppPackage = pkg
                    selectedTriggerAppName = pkg
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
                    if (idx == appPickerActionIndex) appAction.copy(packageName = packageName) else action
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
        if (slotId != -1L) { loadSlotData(slotId) }

        setContent {
            com.autonion.automationcompanion.ui.theme.AppTheme {
                AppSpecificConfigScreen(
                    onBack = { finish() },
                    configuredActions = configuredActionsState,
                    onActionsChanged = { configuredActionsState = it },
                    onPickAppClicked = { actionIndex -> openAppPicker(actionIndex) },
                    onPickTriggerApp = { openTriggerAppPicker() },
                    selectedTriggerApp = selectedTriggerAppPackage,
                    onSave = {
                        saveAppSlot(this, slotId, selectedTriggerAppPackage, configuredActionsState) { finish() }
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
    val isDark = isSystemInDarkTheme()

    // JIT Permissions Logic
    fun checkAndRequestWriteSettings(): Boolean {
        return if (android.provider.Settings.System.canWrite(context)) {
            true
        } else {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = android.net.Uri.parse("package:${context.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Toast.makeText(context, "Grant 'Do Not Disturb Access' permission to use this action.", Toast.LENGTH_LONG).show()
            false
        }
    }

    val handleActionsChanged: (List<ConfiguredAction>) -> Unit = { newActions ->
        val filtered = newActions.filter { action ->
            when (action) {
                is ConfiguredAction.Brightness -> checkAndRequestWriteSettings()
                is ConfiguredAction.Dnd -> checkAndRequestDndAccess()
                else -> true
            }
        }
        onActionsChanged(filtered)
    }

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("App Specific Automation", fontWeight = FontWeight.Bold)
                            Text(
                                "Configure app trigger",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // ═══ Section 1: Trigger App ═══
                ConfigSectionEntry(index = 0) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(title = "Trigger App", icon = Icons.Rounded.Apps, iconTint = AppAccent)
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = onPickTriggerApp,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, AppAccent.copy(alpha = if (isDark) 0.4f else 0.3f))
                        ) {
                            Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp), tint = AppAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                selectedTriggerApp ?: "Select App",
                                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "Automation triggers when this app is opened",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDark) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ═══ Section 2: Actions ═══
                ConfigSectionEntry(index = 1) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(title = "Automations", icon = Icons.Rounded.Bolt, iconTint = ActionsAccent)
                        Spacer(modifier = Modifier.height(8.dp))

                        ActionPicker(
                            context = context,
                            configuredActions = configuredActions,
                            onActionsChanged = handleActionsChanged,
                            onPickContactClicked = { _ -> },
                            onPickAppClicked = onPickAppClicked
                        )
                    }
                }

                // ═══ Save Button ═══
                ConfigSectionEntry(index = 2) {
                    Button(
                        onClick = onSave,
                        enabled = selectedTriggerApp != null,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Automation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

// ── Reusable composables ──

@Composable
private fun ConfigGlassCard(isDark: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF23272A).copy(alpha = 0.92f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        border = if (isDark) BorderStroke(1.5.dp, Color.White.copy(alpha = 0.18f))
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 6.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = {
                CompositionLocalProvider(LocalContentColor provides if (isDark) Color.White else MaterialTheme.colorScheme.onSurface) {
                    content()
                }
            }
        )
    }
}

@Composable
private fun ConfigSectionHeader(title: String, icon: ImageVector, iconTint: Color) {
    val isDark = isSystemInDarkTheme()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(34.dp).clip(CircleShape)
                .background(if (isDark) iconTint.copy(alpha = 0.38f) else iconTint.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (isDark) Color.White else iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConfigSectionEntry(index: Int, content: @Composable ColumnScope.() -> Unit) {
    val animAlpha = remember { Animatable(0f) }
    val slide = remember { Animatable(35f) }
    val delayMs = (index * 80L).coerceAtMost(400L)

    LaunchedEffect(Unit) {
        delay(delayMs)
        launch { animAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
        launch { slide.animateTo(0f, tween(400, easing = FastOutSlowInEasing)) }
    }

    Column(
        modifier = Modifier.graphicsLayer { alpha = animAlpha.value; translationY = slide.value },
        content = content
    )
}

// ── Save logic ──

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
                appName = packageName
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
