package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
fun DeviceManagementScreen() {
    val context = LocalContext.current
    val manager = CrossDeviceAutomationManager.getInstance(context)
    val viewModel = viewModel { DeviceManagementViewModel(manager) }
    val devices by viewModel.devices.collectAsState()
    val isEnabled by viewModel.isFeatureEnabled.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
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
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                        Text(
                            if (isEnabled) "Running in background" else "Disabled",
                            color = if (isEnabled) OnlineGreen.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = isEnabled,
                        onCheckedChange = viewModel::toggleFeature,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AccentPurple,
                            uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                            uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
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

                GlassSettingsCard {
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
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp
                            )
                            Text(
                                "Automatically share copied text",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 12.sp
                            )
                        }

                        Switch(
                            checked = isClipboardSyncEnabled,
                            onCheckedChange = viewModel::toggleClipboardSync,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = AccentBlue,
                                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
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
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Background Settings",
                                color = Color.White,
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
                                contentColor = Color.White.copy(alpha = 0.7f)
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
                                color = Color.White.copy(alpha = 0.3f),
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
                color = Color.White.copy(alpha = 0.6f),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        // ─── Empty + Scanning ───────────────────────
        if (devices.isEmpty()) {
            item {
                ScanningState()
            }
        }

        // ─── Device Cards ───────────────────────────
        itemsIndexed(devices) { index, device ->
            StaggeredDeviceItem(device = device, index = index)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  GLASS SETTINGS CARD
// ═══════════════════════════════════════════════════════════════

@Composable
private fun GlassSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(CardGlass, CardGlass.copy(alpha = 0.35f))
                )
            )
            .background(CardBorder, RoundedCornerShape(16.dp))
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
private fun ScanningState() {
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
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "Make sure devices are on the same network",
                color = Color.White.copy(alpha = 0.3f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  DEVICE CARD
// ═══════════════════════════════════════════════════════════════

@Composable
private fun StaggeredDeviceItem(device: Device, index: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 100L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }
    ) {
        DeviceGlassCard(device)
    }
}

@Composable
private fun DeviceGlassCard(device: Device) {
    val statusColor = when (device.status) {
        DeviceStatus.ONLINE -> OnlineGreen
        DeviceStatus.OFFLINE -> OfflineRed
        DeviceStatus.UNKNOWN -> UnknownGray
    }
    val statusLabel = when (device.status) {
        DeviceStatus.ONLINE -> "Online"
        DeviceStatus.OFFLINE -> "Offline"
        DeviceStatus.UNKNOWN -> "Unknown"
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
            .background(
                Brush.verticalGradient(
                    listOf(CardGlass, CardGlass.copy(alpha = 0.35f))
                )
            )
            .background(CardBorder, RoundedCornerShape(16.dp))
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
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    device.ipAddress,
                    color = Color.White.copy(alpha = 0.4f),
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
                    color = statusColor.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
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
