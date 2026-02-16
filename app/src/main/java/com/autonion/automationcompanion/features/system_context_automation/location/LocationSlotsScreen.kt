package com.autonion.automationcompanion.features.system_context_automation.location

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NearMe
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
import com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionWarningCard
import com.autonion.automationcompanion.features.system_context_automation.shared.utils.PermissionUtils
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSlotsScreen(
    onAddClicked: () -> Unit,
    onEditSlot: (Long) -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).slotDao() }
    val scope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    var recentlyDeleted by remember { mutableStateOf<Slot?>(null) }

    val slots by dao.getSlotsByType("LOCATION").collectAsState(initial = emptyList())
    val now = System.currentTimeMillis()

    val grouped = slots.groupBy {
        val start = it.startMillis ?: 0L
        when {
            start < now -> "Past"
            start < now + 24 * 60 * 60 * 1000 -> "Today"
            else -> "Upcoming"
        }
    }

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
                isLocationPermissionGranted = PermissionUtils.isLocationPermissionGranted(context)
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
                            Text(
                                "Location Automations",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Geofence-based triggers",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (context is android.app.Activity) context.finish()
                        }) {
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
                if (!isLocationPermissionGranted) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        PermissionWarningCard(
                            title = "Location Permission Required",
                            description = "Location automation requires location access to trigger actions.",
                            buttonText = "Allow",
                            onClick = {
                                permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        )
                    }
                }

                if (slots.isEmpty()) {
                    EmptyState()
                } else {
                    var globalIndex = 0

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        grouped.forEach { (section, sectionSlots) ->
                            item {
                                // Staggered section header
                                val idx = globalIndex++
                                StaggeredSlotEntry(index = idx) {
                                    Text(
                                        section,
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                    )
                                }
                            }

                            sectionSlots.forEachIndexed { _, slot ->
                                val idx = globalIndex++
                                item(key = slot.id) {
                                    StaggeredSlotEntry(index = idx) {
                                        SlotCard(
                                            slot = slot,
                                            onToggleEnabled = { enabled ->
                                                if (enabled && !PermissionUtils.isLocationPermissionGranted(context)) {
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "Location permission required to enable automation",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                    permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                                } else {
                                                    scope.launch {
                                                        dao.setEnabled(slot.id, enabled)
                                                    }
                                                }
                                            },
                                            onEdit = { onEditSlot(slot.id) },
                                            onDelete = {
                                                recentlyDeleted = slot
                                                scope.launch {
                                                    dao.delete(slot)
                                                    val result = snackbarHostState.showSnackbar(
                                                        message = "Slot deleted",
                                                        actionLabel = "Undo"
                                                    )
                                                    if (result == SnackbarResult.ActionPerformed) {
                                                        recentlyDeleted?.let {
                                                            dao.insert(it.copy(id = 0))
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
    }
}

/**
 * Staggered entrance animation for list items — fade in + slide up.
 */
@Composable
private fun StaggeredSlotEntry(
    index: Int,
    content: @Composable () -> Unit
) {
    val animAlpha = remember { Animatable(0f) }
    val slide = remember { Animatable(40f) }
    val staggerMs = 60L
    val maxStaggerDelay = 300L
    val delay = (index * staggerMs).coerceAtMost(maxStaggerDelay)

    LaunchedEffect(Unit) {
        delay(delay)
        launch {
            animAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        }
        launch {
            slide.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = animAlpha.value
            translationY = slide.value
        }
    ) {
        content()
    }
}

/**
 * Pulsing empty state with animated location icon.
 */
@Composable
private fun EmptyState() {
    val infiniteTransition = rememberInfiniteTransition(label = "emptyPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        val isDark = isSystemInDarkTheme()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Pulsing ring behind icon
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha * 0.2f)
                        )
                )
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = if (isDark) 0.15f else 0.08f
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.NearMe,
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
                "Tap + to create your first geofence trigger",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Redesigned SlotCard with glassmorphic styling and press-scale animation.
 */
@Composable
private fun SlotCard(
    slot: Slot,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val isDark = isSystemInDarkTheme()

    // Press-scale animation
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "cardScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(animScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onEdit() },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark)
                Color(0xFF1A1D21).copy(alpha = 0.95f)
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
        ),
        border = if (isDark)
            BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 6.dp else 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Location icon in colored container
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.18f else 0.1f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Rounded.MyLocation,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Location Trigger",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Radius: ${slot.radiusMeters?.toInt() ?: 0} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = slot.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp
            )
            Spacer(Modifier.height(14.dp))

            // Schedule row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF7C4DFF).copy(alpha = if (isDark) 0.15f else 0.08f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = Color(0xFF7C4DFF)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "${formatTime(slot.startMillis)} → ${formatTime(slot.endMillis)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(10.dp))

            // Actions row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFFF6D00).copy(alpha = if (isDark) 0.15f else 0.08f),
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = Color(0xFFFF6D00)
                        )
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

            Spacer(Modifier.height(10.dp))

            // Coordinates badge
            if (slot.lat != null && slot.lng != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ) {
                    Text(
                        "📍 ${String.format("%.4f", slot.lat)}, ${String.format("%.4f", slot.lng)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Delete button aligned right
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

private fun formatTime(millis: Long?): String {
    if (millis == null) return "--:--"
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d:%02d".format(
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE)
    )
}
