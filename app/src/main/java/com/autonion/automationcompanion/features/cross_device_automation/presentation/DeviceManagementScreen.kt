package com.autonion.automationcompanion.features.cross_device_automation.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.Device
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceRole
import com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus

// ─── Colors ───────────────────────────────────────────────────
private val CardGlass = Color(0xFF1A1D2E).copy(alpha = 0.55f)
private val CardBorder = Color.White.copy(alpha = 0.08f)
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)
private val OnlineGreen = Color(0xFF66BB6A)
private val OfflineRed = Color(0xFFEF5350)
private val UnknownGray = Color(0xFF9E9E9E)

@Composable
fun DeviceManagementScreen(
    onAccessibilityNeeded: () -> Unit = {}
) {
    val context = LocalContext.current
    val manager = CrossDeviceAutomationManager.getInstance(context)
    val viewModel = viewModel { DeviceManagementViewModel(manager) }
    val devices by viewModel.devices.collectAsState()
    val isEnabled by viewModel.isFeatureEnabled.collectAsState()

    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1A1C1E).copy(alpha = 0.6f)
    val disabledColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF1A1C1E).copy(alpha = 0.4f)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
        // ─── Feature Toggle Card ─────────────────────
        item {
            GlassSettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Icon
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(AccentPurple.copy(alpha = 0.2f), AccentBlue.copy(alpha = 0.15f))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Sensors,
                            contentDescription = null,
                            tint = AccentPurple,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Cross-Device Automation",
                            color = textColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            if (isEnabled) "Running in background" else "Disabled",
                            color = if (isEnabled) OnlineGreen.copy(alpha = 0.8f) else disabledColor,
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = isEnabled,
                        onCheckedChange = viewModel::toggleFeature,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentPurple,
                            uncheckedThumbColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.4f),
                            uncheckedTrackColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }

        // ─── Sub-settings (when enabled) ────────────
        if (isEnabled) {
            // Clipboard Sync
            item {
                val isClipboardSyncEnabled by viewModel.isClipboardSyncEnabled.collectAsState()
                var pendingClipboardEnable by remember { mutableStateOf(false) }
                val isAccessibilityConnected by com.autonion.automationcompanion.AccessibilityRouter.isConnected.collectAsState()

                // Auto-enable clipboard sync when user returns from granting accessibility
                LaunchedEffect(isAccessibilityConnected) {
                    if (pendingClipboardEnable && isAccessibilityConnected) {
                        viewModel.toggleClipboardSync(true)
                        pendingClipboardEnable = false
                    }
                }

                GlassSettingsCard {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = AccentBlue.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Sync Clipboard",
                                    color = textColor,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Text(
                                    if (isClipboardSyncEnabled && !isAccessibilityConnected)
                                        "Requires Accessibility Service"
                                    else
                                        "Automatically share copied text",
                                    color = if (isClipboardSyncEnabled && !isAccessibilityConnected)
                                        Color(0xFFFF6B6B).copy(alpha = 0.8f)
                                    else
                                        disabledColor,
                                    fontSize = 12.sp
                                )
                            }

                            Switch(
                                checked = isClipboardSyncEnabled,
                                onCheckedChange = { enabled ->
                                    if (enabled && !isAccessibilityConnected) {
                                        // Remember the user's intent, then show disclosure
                                        pendingClipboardEnable = true
                                        onAccessibilityNeeded()
                                    } else {
                                        viewModel.toggleClipboardSync(enabled)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = if (isClipboardSyncEnabled && !isAccessibilityConnected)
                                        Color(0xFFFF6B6B).copy(alpha = 0.6f)
                                    else
                                        AccentBlue,
                                    uncheckedThumbColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.4f),
                                    uncheckedTrackColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.1f)
                                )
                            )
                        }

                        // Warning when clipboard sync is enabled but accessibility is off
                        if (isClipboardSyncEnabled && !isAccessibilityConnected) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFFF6B6B).copy(alpha = 0.1f))
                                    .clickable {
                                        pendingClipboardEnable = true
                                        onAccessibilityNeeded()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Accessibility,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6B6B),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Tap to enable Accessibility Service for clipboard sync",
                                    color = Color(0xFFFF6B6B).copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // Battery Optimization
            item {
                val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                val isIgnoringOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)

                GlassSettingsCard {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = textColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Background Settings",
                                color = textColor,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))

                        if (!isIgnoringOptimizations) {
                            Button(
                                onClick = {
                                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6B6B).copy(alpha = 0.2f),
                                    contentColor = Color(0xFFFF6B6B)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Disable Battery Optimization", fontSize = 13.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        OutlinedButton(
                            onClick = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = textColor.copy(alpha = 0.7f)
                            ),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = SolidColor(textColor.copy(alpha = 0.15f))
                            )
                        ) {
                            Text(
                                if (isIgnoringOptimizations) "Verify Background Settings" else "Open App Settings",
                                fontSize = 13.sp
                            )
                        }

                        if (isIgnoringOptimizations) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Battery optimization disabled. If disconnection persists, check 'App Settings > Battery'.",
                                color = secondaryTextColor.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }

        // ─── Section Header ─────────────────────────
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "Discovered Devices",
                color = textColor.copy(alpha = 0.6f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // ─── Empty + Scanning ───────────────────────
        if (devices.isEmpty()) {
            item {
                ScanningState(isDark, textColor)
            }
            // ── Setup Guide (LLM-independent) ──
            item {
                SetupGuideCard(isDark, textColor)
            }
        }

        // ─── Device Cards ───────────────────────────
        itemsIndexed(devices) { index, device ->
            StaggeredDeviceItem(device = device, index = index, onToggleSelection = { viewModel.toggleDeviceSelection(device.id) })
        }
    }
    }
}

// ═══════════════════════════════════════════════════════════════
//  GLASS SETTINGS CARD
// ═══════════════════════════════════════════════════════════════

@Composable
private fun GlassSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val isDark = isSystemInDarkTheme()
    val cardGlass = if (isDark) CardGlass else Color.White.copy(alpha = 0.65f)
    val cardBorder = if (isDark) CardBorder else Color.Black.copy(alpha = 0.05f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (!isDark) Modifier.border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                else Modifier
            )
            .background(
                Brush.verticalGradient(
                    listOf(cardGlass, cardGlass.copy(alpha = 0.35f))
                )
            )
            .background(cardBorder, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  SCANNING ANIMATION
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ScanningState(isDark: Boolean, textColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanPulse"
    )
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // Outer ring
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(ringScale)
                        .clip(CircleShape)
                        .background(AccentBlue.copy(alpha = pulseAlpha * 0.15f))
                )
                // Inner icon
                Icon(
                    Icons.Default.Sensors,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = AccentBlue.copy(alpha = pulseAlpha)
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Scanning for devices...",
                color = textColor.copy(alpha = 0.5f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "Make sure devices are on the same network",
                color = textColor.copy(alpha = 0.3f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  SETUP GUIDE (LLM-independent help for new users)
// ═══════════════════════════════════════════════════════════════

@Composable
private fun SetupGuideCard(isDark: Boolean, textColor: Color) {
    val context = LocalContext.current
    val agentUrl = "https://github.com/Autonion/Autonion-Agent/releases"
    val extensionUrl = "https://github.com/Autonion/Autonion-Extension/releases"

    val cardBorder = if (isDark) CardBorder else Color.Black.copy(alpha = 0.05f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (!isDark) Modifier.border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                else Modifier
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        AccentPurple.copy(alpha = if (isDark) 0.10f else 0.15f),
                        AccentBlue.copy(alpha = if (isDark) 0.08f else 0.12f)
                    )
                )
            )
            .background(cardBorder, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Getting Started",
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }

            // Steps
            SetupStep("1", "Download & install the Autonion Desktop Agent on your PC", textColor)
            SetupStep("2", "Run the Desktop Agent and ensure both devices are on the same WiFi", textColor)
            SetupStep("3", "Your desktop will appear above automatically via mDNS discovery", textColor)
            SetupStep("4", "For browser tasks, also install the Autonion Extension in Chrome", textColor)

            Spacer(Modifier.height(4.dp))

            // Download buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(agentUrl)))
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AccentPurple
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, AccentPurple.copy(alpha = 0.4f)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Computer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Desktop Agent", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(extensionUrl)))
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AccentBlue
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, AccentBlue.copy(alpha = 0.4f)
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Browser Extension", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SetupStep(number: String, text: String, textColor: Color) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(start = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(AccentPurple.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                color = AccentPurple,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = textColor.copy(alpha = 0.6f),
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  DEVICE CARD
// ═══════════════════════════════════════════════════════════════

@Composable
private fun StaggeredDeviceItem(device: Device, index: Int, onToggleSelection: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 100L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }
    ) {
        DeviceGlassCard(device, onToggleSelection = onToggleSelection)
    }
}

@Composable
private fun DeviceGlassCard(device: Device, onToggleSelection: () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val cardGlass = if (isDark) CardGlass else Color.White.copy(alpha = 0.65f)
    val cardBorder = if (isDark) CardBorder else Color.Black.copy(alpha = 0.05f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)

    val statusColor = when (device.status) {
        DeviceStatus.ONLINE -> OnlineGreen
        DeviceStatus.OFFLINE -> OfflineRed
        DeviceStatus.UNKNOWN -> UnknownGray
    }
    val statusLabel = when {
        device.isSelected && device.status == DeviceStatus.ONLINE -> "Connected"
        device.isSelected -> "Selected"
        device.status == DeviceStatus.ONLINE -> "Available"
        device.status == DeviceStatus.OFFLINE -> "Offline"
        else -> "Unknown"
    }

    val iconBgColor = when (device.role) {
        DeviceRole.CONTROLLER -> AccentPurple
        DeviceRole.WORK_DEVICE -> AccentBlue
        DeviceRole.MEDIA_DEVICE -> Color(0xFFFF7043)
        DeviceRole.UNKNOWN -> UnknownGray
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (!isDark) Modifier.border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                else Modifier
            )
            .background(
                Brush.verticalGradient(
                    listOf(cardGlass, cardGlass.copy(alpha = 0.35f))
                )
            )
            .background(
                if (device.isSelected) AccentPurple.copy(alpha = 0.08f) else cardBorder,
                RoundedCornerShape(16.dp)
            )
            .clickable { onToggleSelection() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device icon with colored background
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconBgColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getDeviceIcon(device.role),
                    contentDescription = null,
                    tint = iconBgColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    device.ipAddress,
                    color = textColor.copy(alpha = 0.4f),
                    fontSize = 12.sp
                )
            }

            // Status indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Animated pulse dot for online
                if (device.status == DeviceStatus.ONLINE) {
                    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dotPulse"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = dotAlpha))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.7f))
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    statusLabel,
                    color = (if (device.isSelected) AccentPurple else statusColor).copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.width(8.dp))

            // Selection toggle icon
            Icon(
                imageVector = if (device.isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = if (device.isSelected) "Connected" else "Tap to connect",
                tint = if (device.isSelected) AccentPurple else textColor.copy(alpha = 0.3f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun getDeviceIcon(role: DeviceRole): ImageVector {
    return when (role) {
        DeviceRole.CONTROLLER -> Icons.Default.PhoneAndroid
        DeviceRole.WORK_DEVICE -> Icons.Default.Computer
        DeviceRole.MEDIA_DEVICE -> Icons.Default.Tv
        else -> Icons.Default.PhoneAndroid
    }
}
