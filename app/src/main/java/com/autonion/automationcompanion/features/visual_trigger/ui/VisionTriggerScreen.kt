package com.autonion.automationcompanion.features.visual_trigger.ui

import android.content.Intent as AndroidIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.service.CaptureOverlayService
import com.autonion.automationcompanion.features.visual_trigger.service.VisionExecutionService
import com.autonion.automationcompanion.ui.components.PresetPreviewCard
import com.autonion.automationcompanion.ui.components.AuroraBackground
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.platform.LocalUriHandler
import com.autonion.automationcompanion.ui.components.YouTubeTutorials
import com.autonion.automationcompanion.features.omni_chatbot.ui.LocalStartWalkthrough
import com.autonion.automationcompanion.ui.isTablet
import com.autonion.automationcompanion.ui.rememberWindowWidthSize
import com.autonion.automationcompanion.ui.WindowWidthSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionTriggerScreen(
    onAddClicked: (String) -> Unit,
    onEditPreset: (String) -> Unit,
    onRunPreset: (String) -> Unit,
    onBack: () -> Unit = {},
    viewModel: VisionTriggerViewModel = viewModel()
) {
    val presets by viewModel.presets.collectAsState()
    val isDark = isSystemInDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val startWalkthrough = LocalStartWalkthrough.current
    val uriHandler = LocalUriHandler.current
    val tablet = isTablet()
    val windowWidthSize = rememberWindowWidthSize()

    // Auto-refresh presets every time the screen resumes
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Delete confirmation state
    var presetToDelete by remember { mutableStateOf<VisionPreset?>(null) }

    // Detail sheet state — shows captured image gallery and region crops
    var detailPreset by remember { mutableStateOf<VisionPreset?>(null) }

    // Name dialog state
    var showNameDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    val trimmedNewPresetName = newPresetName.trim()
    val newPresetNameError = when {
        newPresetName.isNotEmpty() && trimmedNewPresetName.isEmpty() -> "Preset name is required"
        trimmedNewPresetName.isNotEmpty() && presets.any { it.name.equals(trimmedNewPresetName, ignoreCase = true) } ->
            "A preset with this name already exists"
        else -> null
    }

    // FAB entrance animation
    val fabScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fabScale.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
    }

    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Visual Triggers",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { uriHandler.openUri(YouTubeTutorials.VISUAL_TRIGGER) }) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Watch Video Tutorial", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { startWalkthrough("visual_trigger") }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Take a Walkthrough", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showNameDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add") },
                    modifier = Modifier.scale(fabScale.value),
                    containerColor = primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        ) { padding ->
            if (presets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    GlowingEyeEmptyState(primary, isDark)
                }
            } else {
                val horizontalPad = when (windowWidthSize) {
                    WindowWidthSize.Expanded -> 32.dp
                    WindowWidthSize.Medium -> 24.dp
                    else -> 16.dp
                }

                if (tablet) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(horizontal = horizontalPad, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItemsIndexed(presets) { index, preset ->
                            var visible by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(index * 50L)
                                visible = true
                            }

                            AnimatedVisibility(
                                visible = visible,
                                enter = fadeIn(tween(300)) +
                                        slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 4 }
                            ) {
                                VisionPresetCard(
                                    preset = preset,
                                    isDark = isDark,
                                    primary = primary,
                                    onClick = { detailPreset = preset },
                                    onRun = { onRunPreset(preset.id) },
                                    onDelete = { presetToDelete = preset },
                                    useVerticalLayout = true
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(presets) { index, preset ->
                            var visible by remember { mutableStateOf(false) }
                            LaunchedEffect(Unit) {
                                kotlinx.coroutines.delay(index * 50L)
                                visible = true
                            }

                            AnimatedVisibility(
                                visible = visible,
                                enter = fadeIn(tween(300)) +
                                        slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 4 }
                            ) {
                                VisionPresetCard(
                                    preset = preset,
                                    isDark = isDark,
                                    primary = primary,
                                    onClick = { detailPreset = preset },
                                    onRun = { onRunPreset(preset.id) },
                                    onDelete = { presetToDelete = preset }
                                )
                            }
                        }

                        item { Spacer(modifier = Modifier.height(88.dp)) }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (presetToDelete != null) {
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            containerColor = if (isDark) Color(0xFF22252B) else MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Delete Preset?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "\"${presetToDelete!!.name}\" and all its regions will be permanently deleted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val preset = presetToDelete!!
                        viewModel.deletePreset(preset.id)
                        // Stop overlay services if they are running
                        val appCtx = viewModel.getApplication<android.app.Application>()
                        appCtx.stopService(AndroidIntent(appCtx, CaptureOverlayService::class.java))
                        appCtx.stopService(AndroidIntent(appCtx, VisionExecutionService::class.java))
                        presetToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // Name preset dialog
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = if (isDark) Color(0xFF22252B) else MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    "Name Your Automation",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                OutlinedTextField(
                    value = newPresetName,
                    onValueChange = { newPresetName = it },
                    label = { Text("Preset Name") },
                    isError = newPresetNameError != null,
                    supportingText = {
                        newPresetNameError?.let { Text(it) }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primary,
                        focusedLabelColor = primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNameDialog = false
                        onAddClicked(trimmedNewPresetName)
                        newPresetName = ""
                    },
                    enabled = trimmedNewPresetName.isNotEmpty() && newPresetNameError == null,
                    colors = ButtonDefaults.buttonColors(containerColor = primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Continue", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    // Preset detail sheet — shows captured images and region templates
    if (detailPreset != null) {
        val currentPreset = detailPreset!!
        VisionPresetDetailSheet(
            preset = currentPreset,
            onDismiss = { detailPreset = null },
            onEdit = { onEditPreset(currentPreset.id) },
            onRun = { onRunPreset(currentPreset.id) },
            onDelete = {
                presetToDelete = currentPreset
                detailPreset = null
            }
        )
    }
}

@Composable
private fun GlowingEyeEmptyState(primary: Color, isDark: Boolean) {
    // Pulsing glow animation (matches Gesture Recording pattern)
    val infiniteTransition = rememberInfiniteTransition(label = "visionPulse")
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

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Outer glow ring
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = pulseAlpha * 0.3f))
            )
            // Inner icon container
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = if (isDark) 0.2f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = primary
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "No Visual Triggers Yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Capture a screenshot and mark regions\nto automate. Tap + Add to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isDark) Color.White.copy(alpha = 0.6f)
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun VisionPresetCard(
    preset: VisionPreset,
    isDark: Boolean,
    primary: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    useVerticalLayout: Boolean = false
) {
    PresetPreviewCard(
        name = preset.name,
        summary = "${preset.regions.size} region${if (preset.regions.size != 1) "s" else ""} • ${preset.executionMode.displayLabel}",
        captureImagePath = preset.captureImagePath,
        isDark = isDark,
        primary = primary,
        onClick = onClick,
        onRun = onRun,
        onDelete = onDelete,
        useVerticalLayout = useVerticalLayout
    )
}
