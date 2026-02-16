package com.autonion.automationcompanion.features.system_context_automation.timeofday.ui

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.automation.actions.builders.ActionBuilder
import com.autonion.automationcompanion.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.features.system_context_automation.timeofday.engine.TimeOfDayReceiver
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════
// TimeOfDayActivity — Slots list
// ═══════════════════════════════════════════════════

class TimeOfDayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.autonion.automationcompanion.ui.theme.AppTheme {
                TimeOfDaySlotsScreen(
                    onBack = { finish() },
                    onAddClicked = { startTimeOfDayConfig() },
                    onEditClicked = { slotId -> startTimeOfDayConfig(slotId) }
                )
            }
        }
    }

    private fun startTimeOfDayConfig(slotId: Long = -1L) {
        val intent = Intent(this, TimeOfDayConfigActivity::class.java)
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

    // FAB entrance animation
    val fabScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(300)
        fabScale.animateTo(
            1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Time-of-Day Automations", fontWeight = FontWeight.Bold)
                            Text(
                                "Scheduled triggers",
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
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onAddClicked,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.scale(fabScale.value)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Automation", fontWeight = FontWeight.SemiBold)
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (slots.isEmpty()) {
                    TimeOfDayEmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(slots, key = { _, slot -> slot.id }) { index, slot ->
                            StaggeredEntry(index = index) {
                                TimeOfDaySlotCard(
                                    slot = slot,
                                    onToggleEnabled = { enabled ->
                                        scope.launch { dao.setEnabled(slot.id, enabled) }
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
        }
    }
}

// ── Pulsing empty state ──

@Composable
private fun TimeOfDayEmptyState() {
    val infiniteTransition = rememberInfiniteTransition(label = "todPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )

    val isDark = isSystemInDarkTheme()
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(120.dp).scale(pulseScale).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha * 0.2f))
                )
                Box(
                    modifier = Modifier
                        .size(88.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.15f else 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "No automations yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Tap + to schedule a time-based trigger",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

// ── Stagger animation wrapper ──

@Composable
private fun StaggeredEntry(index: Int, content: @Composable () -> Unit) {
    val animAlpha = remember { Animatable(0f) }
    val slide = remember { Animatable(40f) }
    val entryDelay = (index * 60L).coerceAtMost(300L)

    LaunchedEffect(Unit) {
        delay(entryDelay)
        launch { animAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
        launch { slide.animateTo(0f, tween(400, easing = FastOutSlowInEasing)) }
    }

    Box(modifier = Modifier.graphicsLayer { alpha = animAlpha.value; translationY = slide.value }) {
        content()
    }
}

// ── Glassmorphic Slot Card ──

@Composable
private fun TimeOfDaySlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val json = remember { kotlinx.serialization.json.Json { ignoreUnknownKeys = true } }
    val config = remember(slot.triggerConfigJson) {
        try {
            slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.TimeOfDay>(it) }
        } catch (e: Exception) { null }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "cardScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(animScale)
            .clickable(interactionSource = interactionSource, indication = null) { onEdit() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) Color(0xFF1A1D21).copy(alpha = 0.95f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
        ),
        border = if (isDark) BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 6.dp else 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF7C4DFF).copy(alpha = if (isDark) 0.18f else 0.1f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Color(0xFF7C4DFF),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        String.format("%02d:%02d", config?.hour ?: 0, config?.minute ?: 0),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (config?.repeatDaily == true) "Repeats daily" else "One-time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = slot.enabled, onCheckedChange = onToggleEnabled)
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 0.5.dp)
            Spacer(Modifier.height(14.dp))

            // Actions row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFFF6D00).copy(alpha = if (isDark) 0.15f else 0.08f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFFFF6D00))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    slot.actions.joinToString { it.javaClass.simpleName.replace("Action", "") },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════
// TimeOfDayConfigActivity — Config screen
// ═══════════════════════════════════════════════════

class TimeOfDayConfigActivity : AppCompatActivity() {

    private var appPickerActionIndex = -1
    private var slotId: Long = -1L

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val packageName = result.data?.getStringExtra("selected_package_name")
            packageName?.let { pkg -> updateAppAction(pkg) }
        }
    }

    private var configuredActionsState by mutableStateOf<List<ConfiguredAction>>(emptyList())

    private var loadedHour by mutableIntStateOf(8)
    private var loadedMinute by mutableIntStateOf(0)
    private var loadedRepeatDaily by mutableStateOf(true)

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
        val intent = Intent(this, AppPickerActivity::class.java)
        appPickerLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        slotId = intent.getLongExtra("slotId", -1L)
        if (slotId != -1L) { loadSlotData(slotId) }

        setContent {
            com.autonion.automationcompanion.ui.theme.AppTheme {
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
                        saveTimeOfDaySlot(this, slotId, h, m, r, actions) { finish() }
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

// ── Accent colors ──
private val TimeAccent = Color(0xFF7C4DFF)    // Purple
private val RepeatAccent = Color(0xFF00BCD4)  // Teal
private val ActionsAccent = Color(0xFFFF6D00) // Orange

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
    onSave: (Int, Int, Boolean, List<ConfiguredAction>) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    var hour by remember(initialHour) { mutableIntStateOf(initialHour) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute) }
    var repeatDaily by remember(initialRepeatDaily) { mutableStateOf(initialRepeatDaily) }

    // ── Material 3 Time Picker dialog state ──
    var showTimePicker by remember { mutableStateOf(false) }

    if (showTimePicker) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        var showDial by remember { mutableStateOf(true) }

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            modifier = Modifier.fillMaxWidth(),
            containerColor = if (isDark) Color(0xFF1E2128) else MaterialTheme.colorScheme.surface,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Select time",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = { showDial = !showDial }) {
                        Icon(
                            imageVector = if (showDial) Icons.Rounded.EditNote else Icons.Rounded.Schedule,
                            contentDescription = if (showDial) "Switch to input" else "Switch to dial",
                            tint = TimeAccent
                        )
                    }
                }
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (showDial) TimePicker(state = state) else TimeInput(state = state)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    hour = state.hour; minute = state.minute; showTimePicker = false
                }) {
                    Text("OK", color = TimeAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(
                        "Cancel",
                        color = if (isDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (isEditing) "Edit Time-of-Day" else "Create Time-of-Day",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Configure schedule trigger",
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

                // ═══ Section 1: Time ═══
                ConfigSectionEntry(index = 0) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(title = "Time", icon = Icons.Rounded.Schedule, iconTint = TimeAccent)
                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            onClick = { showTimePicker = true },
                            color = if (isDark) TimeAccent.copy(alpha = 0.22f) else TimeAccent.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, TimeAccent.copy(alpha = if (isDark) 0.3f else 0.15f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Schedule, contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (isDark) Color.White.copy(alpha = 0.7f) else TimeAccent
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    String.format("%02d:%02d", hour, minute),
                                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (isDark) Color.White else TimeAccent
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    "Tap to change",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDark) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ═══ Section 2: Repeat ═══
                ConfigSectionEntry(index = 1) {
                    ConfigGlassCard(isDark = isDark) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier.size(34.dp).clip(CircleShape)
                                        .background(if (isDark) RepeatAccent.copy(alpha = 0.38f) else RepeatAccent.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Repeat, contentDescription = null,
                                        tint = if (isDark) Color.White else RepeatAccent, modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "Repeat Daily",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = repeatDaily, onCheckedChange = { repeatDaily = it })
                        }
                    }
                }

                // ═══ Section 3: Actions ═══
                ConfigSectionEntry(index = 2) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(title = "Automations", icon = Icons.Rounded.Bolt, iconTint = ActionsAccent)
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

                // ═══ Save Button ═══
                ConfigSectionEntry(index = 3) {
                    Button(
                        onClick = { onSave(hour, minute, repeatDaily, configuredActions) },
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
