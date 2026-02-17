package com.autonion.automationcompanion.features.system_context_automation.app_specific.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import com.autonion.automationcompanion.features.system_context_automation.location.data.models.Slot
import com.autonion.automationcompanion.features.system_context_automation.shared.models.TriggerConfig
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory

class AppSpecificActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.autonion.automationcompanion.ui.theme.AppTheme {
                AppSpecificSlotsScreen(
                    onBack = { finish() },
                    onAddClicked = { startAppSpecificConfig() },
                    onEditClicked = { slotId -> startAppSpecificConfig(slotId) }
                )
            }
        }
    }

    private fun startAppSpecificConfig(slotId: Long = -1L) {
        val intent = Intent(this, AppSpecificConfigActivity::class.java)
        if (slotId != -1L) {
            intent.putExtra("slotId", slotId)
        }
        startActivity(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSpecificSlotsScreen(
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
    val slots = allSlots.filter { it.triggerType == "APP" }

    // Accessibility Service State
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isAccessibilityEnabled by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
                            Text("App Specific Automations", fontWeight = FontWeight.Bold)
                            Text(
                                "App launch triggers",
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
                        if (isAccessibilityEnabled) {
                            onAddClicked()
                        } else {
                            DebugLogger.warning(
                                context,
                                LogCategory.SYSTEM_CONTEXT,
                                "Accessibility Service permission required skipping",
                                "App specific automation requires accessibility service",
                                "AppSpecificActivity"
                            )
                            android.widget.Toast.makeText(context, "Accessibility Service is required", android.widget.Toast.LENGTH_LONG).show()
                            com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.requestAccessibilityPermission(context)
                        }
                    },
                    containerColor = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (isAccessibilityEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                if (!isAccessibilityEnabled) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionWarningCard(
                            title = "Accessibility Service Required",
                            description = "App automation requires Accessibility Service to detect app launches.",
                            buttonText = "Enable Service",
                            onClick = {
                                com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.requestAccessibilityPermission(context)
                            }
                        )
                    }
                }

                if (slots.isEmpty()) {
                    AppEmptyState()
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
                                AppSpecificSlotCard(
                                    slot = slot,
                                    onToggleEnabled = { enabled ->
                                        if (enabled && !isAccessibilityEnabled) {
                                            DebugLogger.warning(
                                                context,
                                                LogCategory.SYSTEM_CONTEXT,
                                                "Accessibility Service permission required skipping",
                                                "Cannot enable app automation without accessibility service",
                                                "AppSpecificActivity"
                                            )
                                            android.widget.Toast.makeText(
                                                context,
                                                "Accessibility Service permission required to enable automation",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                            com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils.requestAccessibilityPermission(context)
                                        } else {
                                            scope.launch { dao.setEnabled(slot.id, enabled) }
                                        }
                                    },
                                    onEdit = { onEditClicked(slot.id) },
                                    onDelete = {
                                        scope.launch {
                                            dao.delete(slot)
                                            
                                            // Log deletion
                                            DebugLogger.info(
                                                context, LogCategory.SYSTEM_CONTEXT,
                                                "App automation deleted",
                                                "Deleted slot ${slot.id}",
                                                "AppSpecificConfig"
                                            )

                                            recentlyDeleted = slot
                                            val result = snackbarHostState.showSnackbar(
                                                message = "Slot deleted",
                                                actionLabel = "Undo"
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                recentlyDeleted?.let { 
                                                    val newId = dao.insert(it.copy(id = 0)) 
                                                    // Log undo
                                                    DebugLogger.success(
                                                        context, LogCategory.SYSTEM_CONTEXT,
                                                        "App automation restored",
                                                        "Restored slot ${slot.id} as $newId",
                                                        "AppSpecificConfig"
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
private fun AppEmptyState() {
    val infiniteTransition = rememberInfiniteTransition(label = "appPulse")
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
                        Icons.Rounded.Apps,
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
                "Tap + to create an app-triggered automation",
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
private fun AppSpecificSlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val json = remember { kotlinx.serialization.json.Json { ignoreUnknownKeys = true } }
    val config = remember(slot.triggerConfigJson) {
        try {
            slot.triggerConfigJson?.let { json.decodeFromString<TriggerConfig.App>(it) }
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
                    color = Color(0xFF4CAF50).copy(alpha = if (isDark) 0.18f else 0.1f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Apps,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        config?.appName ?: config?.packageName ?: "Unknown App",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Triggered on app open",
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
