package com.autonion.automationcompanion.features.system_context_automation.wifi.ui

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
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionWarningCard
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════
// WiFiActivity — Slots list
// ═══════════════════════════════════════════════════

class WiFiActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.autonion.automationcompanion.ui.theme.AppTheme {
                WiFiSlotsScreen(
                    onBack = { finish() },
                    onAddClicked = { startWiFiConfig() },
                    onEditClicked = { slotId -> startWiFiConfig(slotId) }
                )
            }
        }
    }

    private fun startWiFiConfig(slotId: Long = -1L) {
        val intent = Intent(this, WiFiConfigActivity::class.java)
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
    ) { isGranted -> isLocationPermissionGranted = isGranted }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isLocationPermissionGranted = PermissionUtils.isLocationPermissionGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
                            Text("Wi-Fi Automations", fontWeight = FontWeight.Bold)
                            Text(
                                "Network-based triggers",
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
                    onClick = {
                        if (!isLocationPermissionGranted) {
                            permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        } else if (!PermissionUtils.isAccessibilityServiceEnabled(context)) {
                            Toast.makeText(context, "Accessibility Service required for automation actions", Toast.LENGTH_LONG).show()
                            PermissionUtils.requestAccessibilityPermission(context)
                        } else {
                            onAddClicked()
                        }
                    },
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
                if (!isLocationPermissionGranted) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        PermissionWarningCard(
                            title = "Location Permission Required",
                            description = "Wi-Fi automation needs location permission to detect network SSID (Android requirement).",
                            buttonText = "Allow",
                            onClick = { permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION) }
                        )
                    }
                }

                if (slots.isEmpty()) {
                    WiFiEmptyState()
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
                                WiFiSlotCard(
                                    slot = slot,
                                    onToggleEnabled = { enabled ->
                                        if (enabled) {
                                            if (!PermissionUtils.isLocationPermissionGranted(context)) {
                                                Toast.makeText(context, "Location permission required", Toast.LENGTH_LONG).show()
                                                permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                                return@WiFiSlotCard
                                            }
                                            if (!PermissionUtils.isAccessibilityServiceEnabled(context)) {
                                                Toast.makeText(context, "Accessibility Service required", Toast.LENGTH_LONG).show()
                                                PermissionUtils.requestAccessibilityPermission(context)
                                                return@WiFiSlotCard
                                            }
                                        }
                                        scope.launch { dao.setEnabled(slot.id, enabled) }
                                    },
                                    onEdit = { onEditClicked(slot.id) },
                                    onDelete = {
                                        scope.launch {
                                            dao.delete(slot)
                                            recentlyDeleted = slot

                                            // Log deletion
                                            com.autonion.automationcompanion.features.automation_debugger.DebugLogger.info(
                                                context, com.autonion.automationcompanion.features.automation_debugger.data.LogCategory.SYSTEM_CONTEXT,
                                                "Wi-Fi automation deleted",
                                                "Deleted slot ${slot.id}",
                                                "WiFiSlotsScreen"
                                            )

                                            val result = snackbarHostState.showSnackbar(
                                                message = "Slot deleted",
                                                actionLabel = "Undo"
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                recentlyDeleted?.let { 
                                                    val newId = dao.insert(it.copy(id = 0)) 
                                                    
                                                    // Log undo
                                                    com.autonion.automationcompanion.features.automation_debugger.DebugLogger.success(
                                                        context, com.autonion.automationcompanion.features.automation_debugger.data.LogCategory.SYSTEM_CONTEXT,
                                                        "Wi-Fi automation restored",
                                                        "Restored slot ${slot.id} as $newId",
                                                        "WiFiSlotsScreen"
                                                    )
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
        }
    }
}

// ── Pulsing empty state ──

@Composable
private fun WiFiEmptyState() {
    val infiniteTransition = rememberInfiniteTransition(label = "wifiPulse")
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
                        Icons.Rounded.Wifi,
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
                "Tap + to create a Wi-Fi trigger",
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
private fun WiFiSlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val json = remember { kotlinx.serialization.json.Json { ignoreUnknownKeys = true } }
    val config = remember(slot.triggerConfigJson) {
        try {
            slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.WiFi>(it) }
        } catch (e: Exception) { null }
    }

    val isConnected = config?.connectionState == TriggerConfig.WiFi.ConnectionState.CONNECTED

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
                    color = Color(0xFF2196F3).copy(alpha = if (isDark) 0.18f else 0.1f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                            contentDescription = null,
                            tint = Color(0xFF2196F3),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isConnected) "Wi-Fi Connected" else "Wi-Fi Disconnected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!config?.optionalSsid.isNullOrBlank()) {
                        Text(
                            "Network: ${config?.optionalSsid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
// WiFiConfigActivity — Config screen
// ═══════════════════════════════════════════════════

class WiFiConfigActivity : AppCompatActivity() {

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
                        if (!PermissionUtils.isLocationPermissionGranted(this)) {
                            permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        } else {
                            saveWiFiSlot(this, slotId, cState, ssid, actions) { finish() }
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

// ── Accent colors ──
private val WiFiAccent = Color(0xFF2196F3)    // Blue
private val NetworkAccent = Color(0xFF009688) // Teal
private val ActionsAccent = Color(0xFFFF6D00) // Orange

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
                            Text("Create Wi-Fi Automation", fontWeight = FontWeight.Bold)
                            Text(
                                "Configure network trigger",
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

                // ═══ Section 1: Connection State ═══
                ConfigSectionEntry(index = 0) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(title = "Trigger", icon = Icons.Rounded.Wifi, iconTint = WiFiAccent)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = connectionState == TriggerConfig.WiFi.ConnectionState.CONNECTED,
                                onClick = { onConnectionStateChanged(TriggerConfig.WiFi.ConnectionState.CONNECTED) },
                                label = { Text("Connected") },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = WiFiAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = WiFiAccent
                                )
                            )
                            FilterChip(
                                selected = connectionState == TriggerConfig.WiFi.ConnectionState.DISCONNECTED,
                                onClick = { onConnectionStateChanged(TriggerConfig.WiFi.ConnectionState.DISCONNECTED) },
                                label = { Text("Disconnected") },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = WiFiAccent.copy(alpha = 0.2f),
                                    selectedLabelColor = WiFiAccent
                                )
                            )
                        }
                    }
                }

                // ═══ Section 2: SSID Filter ═══
                ConfigSectionEntry(index = 1) {
                    ConfigGlassCard(isDark = isDark) {
                        ConfigSectionHeader(title = "Network (Optional)", icon = Icons.Rounded.Router, iconTint = NetworkAccent)
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = ssidFilter,
                            onValueChange = onSsidFilterChanged,
                            label = { Text("Leave empty to match any network") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NetworkAccent,
                                cursorColor = NetworkAccent
                            )
                        )
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
                            onActionsChanged = handleActionsChanged,
                            onPickContactClicked = { _ -> },
                            onPickAppClicked = onPickAppClicked
                        )
                    }
                }

                // ═══ Save Button ═══
                ConfigSectionEntry(index = 3) {
                    Button(
                        onClick = { onSaveClicked(connectionState, ssidFilter.ifBlank { null }, configuredActions) },
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
