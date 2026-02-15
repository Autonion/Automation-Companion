package com.autonion.automationcompanion.features.system_context_automation.battery.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.autonion.automationcompanion.features.automation.actions.builders.ActionBuilder
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.features.system_context_automation.battery.engine.BatteryServiceManager
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private var slotId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        slotId = intent.getLongExtra("slotId", -1L)
        if (slotId != -1L) {
            loadSlotData(slotId)
        }

        setContent {
            com.autonion.automationcompanion.ui.theme.AppTheme {
                BatteryConfigScreen(
                    onBack = { finish() },
                    configuredActions = configuredActionsState,
                    onActionsChanged = { configuredActionsState = it },
                    onPickAppClicked = { actionIndex -> openAppPicker(actionIndex) },
                    initialBatteryPercentage = loadedBatteryPercentage,
                    initialThresholdType = loadedThresholdType,
                    isEditing = slotId != -1L,
                    onSave = { percentage, type, actions ->
                        saveBatterySlot(this, slotId, percentage, type, actions) {
                            finish()
                        }
                    }
                )
            }
        }
    }

    private var loadedBatteryPercentage by mutableIntStateOf(20)
    private var loadedThresholdType by mutableStateOf(TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW)

    private fun loadSlotData(id: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.get(this@BatteryConfigActivity).slotDao()
            val slot = dao.getById(id) ?: return@launch

            val json = Json { ignoreUnknownKeys = true }
            val config = slot.triggerConfigJson?.let {
                try { json.decodeFromString<TriggerConfig.Battery>(it) } catch (e: Exception) { null }
            }

            val loadedActions = ActionBuilder.toConfiguredActions(this@BatteryConfigActivity, slot.actions)

            CoroutineScope(Dispatchers.Main).launch {
                configuredActionsState = loadedActions
                config?.let {
                    loadedBatteryPercentage = it.batteryPercentage
                    loadedThresholdType = it.thresholdType
                }
            }
        }
    }
}

// Accent colors
private val BatteryAccent = Color(0xFF4CAF50)  // Green
private val TriggerAccent = Color(0xFF7C4DFF)  // Purple
private val ActionsAccent = Color(0xFFFF6D00)  // Orange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatteryConfigScreen(
    onBack: () -> Unit,
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    onPickAppClicked: (Int) -> Unit,
    initialBatteryPercentage: Int = 20,
    initialThresholdType: TriggerConfig.Battery.ThresholdType = TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW,
    isEditing: Boolean = false,
    onSave: (Int, TriggerConfig.Battery.ThresholdType, List<ConfiguredAction>) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    var batteryPercentage by remember(initialBatteryPercentage) { mutableIntStateOf(initialBatteryPercentage) }
    var thresholdType by remember(initialThresholdType) { mutableStateOf(initialThresholdType) }

    val animatedPercentage by animateIntAsState(
        targetValue = batteryPercentage,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "percentAnim"
    )

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (isEditing) "Edit Battery Automation" else "Create Battery Automation",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Configure battery trigger",
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

                // ═══════════ Section 1: Battery Percentage ═══════════
                ConfigSectionEntry(index = 0) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(
                            title = "Battery Level",
                            icon = Icons.Rounded.BatteryChargingFull,
                            iconTint = BatteryAccent
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "$animatedPercentage%",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = BatteryAccent
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Slider(
                            value = batteryPercentage.toFloat(),
                            onValueChange = { batteryPercentage = it.toInt() },
                            valueRange = 1f..100f,
                            steps = 99,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = BatteryAccent,
                                activeTrackColor = BatteryAccent
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("1%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            Text("100%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }

                // ═══════════ Section 2: Trigger Type ═══════════
                ConfigSectionEntry(index = 1) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(
                            title = "Trigger Type",
                            icon = Icons.Rounded.Tune,
                            iconTint = TriggerAccent
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = thresholdType == TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW,
                                onClick = { thresholdType = TriggerConfig.Battery.ThresholdType.REACHES_OR_BELOW },
                                label = { Text("At or Below") },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TriggerAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = TriggerAccent
                                )
                            )
                            FilterChip(
                                selected = thresholdType == TriggerConfig.Battery.ThresholdType.REACHES_OR_ABOVE,
                                onClick = { thresholdType = TriggerConfig.Battery.ThresholdType.REACHES_OR_ABOVE },
                                label = { Text("At or Above") },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = TriggerAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = TriggerAccent
                                )
                            )
                        }
                    }
                }

                // ═══════════ Section 3: Actions ═══════════
                ConfigSectionEntry(index = 2) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(
                            title = "Automations",
                            icon = Icons.Rounded.Bolt,
                            iconTint = ActionsAccent
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        ActionPicker(
                            context = context,
                            configuredActions = configuredActions,
                            onActionsChanged = onActionsChanged,
                            onPickContactClicked = { _ -> },
                            onPickAppClicked = onPickAppClicked
                        )
                    }
                }

                // ═══════════ Save Button ═══════════
                ConfigSectionEntry(index = 3) {
                    Button(
                        onClick = { onSave(batteryPercentage, thresholdType, configuredActions) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
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

// ── Reusable composables for config screens ──

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

private fun saveBatterySlot(
    context: Context,
    slotId: Long,
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
                id = if (slotId != -1L) slotId else 0,
                triggerType = "BATTERY",
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

            // Start battery monitoring service
            BatteryServiceManager.startMonitoringIfNeeded(context)

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Battery automation saved", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
        } catch (e: Exception) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Error saving automation: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
