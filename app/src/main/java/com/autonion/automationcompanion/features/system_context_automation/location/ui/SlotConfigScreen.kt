package com.autonion.automationcompanion.features.system_context_automation.location.ui

import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.automation.actions.models.AutomationAction
import com.autonion.automationcompanion.features.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.features.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.automation.actions.builders.ActionBuilder
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek

// Section accent colors
private val LocationAccent = Color(0xFF00B0FF)  // Blue
private val ScheduleAccent = Color(0xFF7C4DFF)  // Purple
private val RadiusAccent = Color(0xFF00BFA5)    // Teal
private val ActionsAccent = Color(0xFFFF6D00)   // Orange
private val ReminderAccent = Color(0xFFFFAB00)  // Amber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotConfigScreen(
    title: String,
    latitude: String,
    longitude: String,
    radiusMeters: Int,
    startLabel: String,
    endLabel: String,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    onLatitudeChanged: (String) -> Unit,
    onLongitudeChanged: (String) -> Unit,
    onRadiusChanged: (Int) -> Unit,
    onStartTimeChanged: (Int, Int) -> Unit,
    onEndTimeChanged: (Int, Int) -> Unit,
    onSaveClicked: (Int, List<AutomationAction>) -> Unit,
    onPickFromMapClicked: () -> Unit,
    onPickContactClicked: (actionIndex: Int) -> Unit,
    onPickAppClicked: (actionIndex: Int) -> Unit,
    remindBeforeMinutes: String,
    onRemindBeforeMinutesChange: (String) -> Unit,
    selectedDays: Set<String>,
    onSelectedDaysChange: (Set<String>) -> Unit,
    configuredActions: List<ConfiguredAction>,
    onActionsChanged: (List<ConfiguredAction>) -> Unit,
    volumeEnabled: Boolean,
    context: android.content.Context
) {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()
    val isDark = isSystemInDarkTheme()

    // ── Material 3 Time Picker dialog state ──
    var showTimePickerFor by remember { mutableStateOf<String?>(null) } // "start" or "end" or null

    if (showTimePickerFor != null) {
        val isStart = showTimePickerFor == "start"
        val initHour = if (isStart) (if (startHour >= 0) startHour else 9) else (if (endHour >= 0) endHour else 17)
        val initMinute = if (isStart) (if (startMinute >= 0) startMinute else 0) else (if (endMinute >= 0) endMinute else 0)

        M3TimePickerDialog(
            initialHour = initHour,
            initialMinute = initMinute,
            accentColor = ScheduleAccent,
            onConfirm = { h, m ->
                if (isStart) onStartTimeChanged(h, m) else onEndTimeChanged(h, m)
                showTimePickerFor = null
            },
            onDismiss = { showTimePickerFor = null }
        )
    }

    // Animated radius text
    val animatedRadius by animateIntAsState(
        targetValue = radiusMeters,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "radiusAnim"
    )

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, fontWeight = FontWeight.Bold)
                            Text(
                                "Configure geofence trigger",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (ctx is android.app.Activity) ctx.finish()
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
            containerColor = Color.Transparent
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                // ═══════════ Section 1: Location ═══════════
                SectionEntry(index = 0) {
                    GlassCard(isDark = isDark) {
                        SectionHeader(
                            title = "Location Coordinates",
                            icon = Icons.Rounded.LocationOn,
                            iconTint = LocationAccent
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val textFieldColors = if (isDark) {
                                OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedLabelColor = Color.White,
                                    unfocusedLabelColor = Color.White,
                                    focusedBorderColor = Color.White,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                    cursorColor = Color.White
                                )
                            } else {
                                OutlinedTextFieldDefaults.colors()
                            }
                            OutlinedTextField(
                                value = latitude,
                                onValueChange = onLatitudeChanged,
                                label = { Text("Latitude", color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = textFieldColors
                            )
                            OutlinedTextField(
                                value = longitude,
                                onValueChange = onLongitudeChanged,
                                label = { Text("Longitude", color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = textFieldColors
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = onPickFromMapClicked,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LocationAccent.copy(alpha = if (isDark) 0.2f else 0.12f),
                                contentColor = if (isDark) Color.White else LocationAccent
                            )
                        ) {
                            Icon(Icons.Rounded.Map, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (isDark) Color.White else LocationAccent)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pick from Map", fontWeight = FontWeight.SemiBold, color = if (isDark) Color.White else LocationAccent)
                        }
                    }
                }

                // ═══════════ Section 2: Schedule ═══════════
                SectionEntry(index = 1) {
                    GlassCard(isDark = isDark) {
                        SectionHeader(
                            title = "Schedule",
                            icon = Icons.Rounded.Schedule,
                            iconTint = ScheduleAccent
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TimePickItem(
                                label = "Start Time",
                                time = startLabel,
                                onClick = { showTimePickerFor = "start" },
                                accentColor = ScheduleAccent
                            )
                            TimePickItem(
                                label = "End Time",
                                time = endLabel,
                                onClick = { showTimePickerFor = "end" },
                                accentColor = ScheduleAccent
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            "Active Days",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        androidx.compose.foundation.lazy.LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val days = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
                            items(days.size) { index ->
                                val day = days[index]
                                val isSelected = selectedDays.contains(day)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        onSelectedDaysChange(
                                            if (isSelected) selectedDays - day
                                            else selectedDays + day
                                        )
                                    },
                                    label = { Text(day, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = ScheduleAccent.copy(alpha = 0.2f),
                                        selectedLabelColor = ScheduleAccent
                                    )
                                )
                            }
                        }

                        if (selectedDays.isEmpty()) {
                            Text(
                                "⚠ Select at least one day",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                // ═══════════ Section 3: Radius ═══════════
                SectionEntry(index = 2) {
                    GlassCard(isDark = isDark) {
                        SectionHeader(
                            title = "Geofence Radius",
                            icon = Icons.Rounded.GpsFixed,
                            iconTint = RadiusAccent
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Animated radius display
                        Text(
                            "$animatedRadius m",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = RadiusAccent
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Slider(
                            value = radiusMeters.toFloat(),
                            onValueChange = { onRadiusChanged(it.toInt()) },
                            valueRange = 50f..2000f,
                            steps = 39,
                            colors = SliderDefaults.colors(
                                thumbColor = RadiusAccent,
                                activeTrackColor = RadiusAccent
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "50 m",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                "2000 m",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // ═══════════ Section 4: Actions ═══════════
                SectionEntry(index = 3) {
                    GlassCard(isDark = isDark) {
                        SectionHeader(
                            title = "Automations",
                            icon = Icons.Rounded.Bolt,
                            iconTint = ActionsAccent
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        ActionPicker(
                            configuredActions = configuredActions,
                            onActionsChanged = onActionsChanged,
                            onPickContactClicked = onPickContactClicked,
                            onPickAppClicked = onPickAppClicked,
                            dndDisabledReason = if (volumeEnabled) "Disabled: Volume is active" else null,
                            context = context
                        )
                    }
                }

                // ═══════════ Section 5: Reminder ═══════════
                SectionEntry(index = 4) {
                    GlassCard(isDark = isDark) {
                        SectionHeader(
                            title = "Reminder",
                            icon = Icons.Rounded.NotificationsActive,
                            iconTint = ReminderAccent
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = remindBeforeMinutes,
                            onValueChange = onRemindBeforeMinutesChange,
                            label = { Text("Remind before (minutes)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                // ═══════════ Save Button ═══════════
                SectionEntry(index = 5) {
                    val hasAnyAction = ActionBuilder.hasAnyValidAction(configuredActions)
                    Button(
                        enabled = hasAnyAction,
                        onClick = {
                            val actions = ActionBuilder.buildActions(configuredActions)
                            onSaveClicked(
                                remindBeforeMinutes.toIntOrNull() ?: 15,
                                actions
                            )
                        },
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
                        Text(
                            "Save Automation",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    if (!hasAnyAction) {
                        Text(
                            "⚠ Enable at least one automation",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

/* ═══════════ Reusable Components ═══════════ */

/**
 * Glassmorphic card with semi-transparent background and dark-mode border.
 */
@Composable
private fun GlassCard(
    isDark: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark)
                Color(0xFF23272A).copy(alpha = 0.92f) // Much darker and more opaque for contrast
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        border = if (isDark)
            BorderStroke(1.5.dp, Color.White.copy(alpha = 0.18f)) // Stronger border
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 6.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = {
                val isDark = isSystemInDarkTheme()
                CompositionLocalProvider(LocalContentColor provides if (isDark) Color.White else MaterialTheme.colorScheme.onSurface) {
                    content()
                }
            }
        )
    }
}

/**
 * Section header with colored icon in a round container.
 */
@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    iconTint: Color
) {
    val isDark = isSystemInDarkTheme()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (isDark) iconTint.copy(alpha = 0.38f) else iconTint.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDark) Color.White else iconTint,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Staggered entrance animation for each section.
 */
@Composable
private fun SectionEntry(
    index: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    val animAlpha = remember { Animatable(0f) }
    val slide = remember { Animatable(35f) }
    val staggerMs = 80L
    val maxStaggerDelay = 400L
    val delayMs = (index * staggerMs).coerceAtMost(maxStaggerDelay)

    LaunchedEffect(Unit) {
        delay(delayMs)
        launch {
            animAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        }
        launch {
            slide.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
        }
    }

    val isDark = isSystemInDarkTheme()
    Column(
        modifier = Modifier.graphicsLayer {
            alpha = animAlpha.value
            translationY = slide.value
        }
            .background(if (isDark) Color.Black.copy(alpha = 0.04f) else Color.Transparent),
        content = content
    )
}

/**
 * Redesigned time picker item with accent-colored background.
 */
@Composable
fun TimePickItem(
    label: String,
    time: String,
    onClick: () -> Unit,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val isDark = isSystemInDarkTheme()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            onClick = onClick,
            color = if (isDark) accentColor.copy(alpha = 0.32f) else accentColor.copy(alpha = 0.08f),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, accentColor.copy(alpha = if (isDark) 0.3f else 0.15f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isDark) Color.White.copy(alpha = 0.7f) else accentColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    time,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isDark) Color.White else accentColor
                )
            }
        }
    }
}

/**
 * Material 3 Time Picker Dialog — modern replacement for legacy TimePickerDialog.
 * Features dial/input mode toggle and themed styling.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun M3TimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    accentColor: Color,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val state = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )
    var showDial by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                        tint = accentColor
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (showDial) {
                    TimePicker(state = state)
                } else {
                    TimeInput(state = state)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("OK", color = accentColor, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "Cancel",
                    color = if (isDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

// Utility extension to lighten a color for dark mode accents
private fun Color.lighten(amount: Float): Color {
    val a = alpha
    val r = red + (1f - red) * amount
    val g = green + (1f - green) * amount
    val b = blue + (1f - blue) * amount
    return Color(r, g, b, a)
}
