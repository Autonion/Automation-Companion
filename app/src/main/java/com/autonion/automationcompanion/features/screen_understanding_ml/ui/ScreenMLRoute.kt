package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.PresetRepository
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationPreset
import com.autonion.automationcompanion.ui.components.AuroraBackground
import androidx.compose.material.icons.outlined.Info
import com.autonion.automationcompanion.features.omni_chatbot.ui.LocalStartWalkthrough
import kotlinx.coroutines.launch

/**
 * Compose-based route for Screen Context AI (Screen ML).
 *
 * Replaces the Activity-based PresetDashboardActivity so the companion
 * overlay (Shimeji) stays visible during walkthroughs — the UI remains
 * inside the single-Activity navigation graph rather than stacking
 * a new Activity on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenMLRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { PresetRepository(context) }

    val presets = remember { mutableStateListOf<AutomationPreset>() }
    var showDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                presets.clear()
                presets.addAll(repository.getAllPresets())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showDialog) {
        NewAutomationDialog(
            onDismiss = { showDialog = false },
            onConfirm = { name ->
                showDialog = false
                val intent = Intent(context, SetupFlowActivity::class.java)
                intent.putExtra("presetName", name)
                context.startActivity(intent)
            }
        )
    }

    ScreenMLDashboardContent(
        presets = presets,
        onBack = onBack,
        onAddClick = { showDialog = true },
        onDelete = { preset ->
            repository.deletePreset(preset.id)
            presets.remove(preset)
        },
        onPlay = { preset ->
            val intent = Intent(context, SetupFlowActivity::class.java).apply {
                putExtra("ACTION_REQUEST_PERMISSION_PLAY_PRESET", preset.id)
                putExtra("presetName", preset.name)
            }
            context.startActivity(intent)
        },
        onTest = { modelFile ->
            val intent = Intent(context, SetupFlowActivity::class.java).apply {
                putExtra("presetName", "_debug_test")
                putExtra("debugMode", true)
                putExtra("modelFile", modelFile)
            }
            context.startActivity(intent)
        }
    )
}

// ─── Dashboard Content ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenMLDashboardContent(
    presets: List<AutomationPreset>,
    onBack: () -> Unit,
    onAddClick: () -> Unit,
    onDelete: (AutomationPreset) -> Unit,
    onPlay: (AutomationPreset) -> Unit,
    onTest: (String) -> Unit = {}
) {
    val fabScale = remember { Animatable(0f) }
    val startWalkthrough = LocalStartWalkthrough.current
    LaunchedEffect(Unit) {
        fabScale.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Agent Automations") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    actions = {
                        IconButton(onClick = { startWalkthrough("screen_ml") }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Take a Walkthrough", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { onTest("yolov11s_model.tflite") }) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = "Test Detection",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onAddClick,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add") },
                    modifier = Modifier.scale(fabScale.value),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            },
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize()
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                if (presets.isEmpty()) {
                    AgentEmptyState()
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(
                            presets,
                            key = { _, preset -> preset.id }
                        ) { index, preset ->
                            var itemVisible by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(index * 50L)
                                itemVisible = true
                            }
                            AnimatedVisibility(
                                visible = itemVisible,
                                enter = fadeIn(tween(300)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 4 }
                            ) {
                                AgentPresetItem(
                                    preset = preset,
                                    onDelete = { onDelete(preset) },
                                    onPlay = { onPlay(preset) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Empty State ────────────────────────────────────────

@Composable
private fun AgentEmptyState() {
    val isDark = isSystemInDarkTheme()
    val infiniteTransition = rememberInfiniteTransition(label = "agentPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "pulseAlpha"
    )

    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(pulseScale)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha * 0.3f),
                            CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.2f else 0.12f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ViewQuilt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.size(24.dp))
            Text(
                text = "No agent automations yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = "Tap + Add to create a new\nscreen-understanding automation",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) Color.White.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── Preset Card ────────────────────────────────────────

@Composable
private fun AgentPresetItem(
    preset: AutomationPreset,
    onDelete: () -> Unit,
    onPlay: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )
    val isDark = isSystemInDarkTheme()

    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interactionSource, indication = null) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = if (isDark) BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${preset.steps.size} steps • ${preset.scope}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            IconButton(onClick = onPlay, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Run",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── New Automation Dialog ──────────────────────────────

@Composable
private fun NewAutomationDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val scale = remember { Animatable(0.9f) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        alpha.animateTo(1f, tween(220))
    }

    fun dismissThen(callback: () -> Unit) {
        scope.launch {
            scale.animateTo(0.95f, tween(120))
            alpha.animateTo(0f, tween(120))
            callback()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "New Automation",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Preset name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    singleLine = true
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { dismissThen(onDismiss) }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            dismissThen {
                                if (name.isNotBlank()) onConfirm(name.trim())
                            }
                        },
                        enabled = name.isNotBlank()
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}
