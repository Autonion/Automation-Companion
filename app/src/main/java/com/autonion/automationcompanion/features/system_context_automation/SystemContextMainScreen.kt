package com.autonion.automationcompanion.features.system_context_automation

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsSystemDaydream
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.system_context_automation.app_specific.ui.AppSpecificActivity
import com.autonion.automationcompanion.features.system_context_automation.battery.ui.BatterySlotsActivity
import com.autonion.automationcompanion.features.system_context_automation.location.LocationSlotsActivity
import com.autonion.automationcompanion.features.system_context_automation.timeofday.ui.TimeOfDayActivity
import com.autonion.automationcompanion.features.system_context_automation.wifi.ui.WiFiActivity
import com.autonion.automationcompanion.ui.components.AuroraBackground
import com.autonion.automationcompanion.ui.components.FeatureCard
import androidx.compose.material.icons.outlined.Info
import com.autonion.automationcompanion.features.omni_chatbot.ui.LocalStartWalkthrough
import com.autonion.automationcompanion.ui.isTablet
import com.autonion.automationcompanion.ui.rememberWindowWidthSize
import com.autonion.automationcompanion.ui.WindowWidthSize
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.ui.components.FeatureTipSheet
import com.autonion.automationcompanion.ui.components.YouTubeTutorials
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemContextMainScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val startWalkthrough = LocalStartWalkthrough.current
    val uriHandler = LocalUriHandler.current
    val tablet = isTablet()
    val windowWidthSize = rememberWindowWidthSize()

    // ── First-visit Feature Tip ──
    val onboardingPrefs = remember { OnboardingPreferences.getInstance(context) }
    var showTip by remember { mutableStateOf(!onboardingPrefs.hasTipBeenSeen("system_context")) }

    if (showTip) {
        FeatureTipSheet(
            title = "System Context Automation",
            tips = listOf(
                "Tap a **category card** to set up automation rules",
                "Rules run **automatically** in the background when conditions are met",
                "Combine triggers like **location + time** for powerful automations"
            ),
            icon = androidx.compose.material.icons.Icons.Default.SettingsSystemDaydream,
            iconColor = androidx.compose.ui.graphics.Color(0xFF448AFF),
            youtubeLink = YouTubeTutorials.SYSTEM_CONTEXT,
            onDismiss = { onboardingPrefs.markTipSeen("system_context"); showTip = false },
            onShowWalkthrough = { showTip = false; startWalkthrough("system_context") }
        )
    }

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("System Context Automation", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { uriHandler.openUri(YouTubeTutorials.SYSTEM_CONTEXT) }) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Watch Video Tutorial")
                        }
                        IconButton(onClick = { startWalkthrough("system_context") }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Take a Walkthrough")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize()
        ) { padding ->
            
            val triggerItems = remember {
                listOf(
                    TriggerItem(
                        title = "Location-Based",
                        description = "Trigger on geofence entry/exit",
                        icon = Icons.Default.LocationOn,
                        accentColor = Color(0xFF4285F4), // Google Blue
                        onClick = { context.startActivity(Intent(context, LocationSlotsActivity::class.java)) }
                    ),
                    TriggerItem(
                        title = "Battery Level",
                        description = "Trigger when battery reaches threshold",
                        icon = Icons.Default.BatteryStd,
                        accentColor = Color(0xFF34A853), // Green
                        onClick = { context.startActivity(Intent(context, BatterySlotsActivity::class.java)) }
                    ),
                    TriggerItem(
                        title = "Time of Day",
                        description = "Trigger at specific time daily",
                        icon = Icons.Default.Schedule,
                        accentColor = Color(0xFFF9AB00), // Amber
                        onClick = { context.startActivity(Intent(context, TimeOfDayActivity::class.java)) }
                    ),
                    TriggerItem(
                        title = "Wi-Fi Connectivity",
                        description = "Trigger on Wi-Fi connect/disconnect",
                        icon = Icons.Default.Wifi,
                        accentColor = Color(0xFF00ACC1), // Cyan
                        onClick = { context.startActivity(Intent(context, WiFiActivity::class.java)) }
                    ),
                    TriggerItem(
                        title = "App Specific",
                        description = "Trigger when app opens/closes",
                        icon = Icons.Default.Apps,
                        accentColor = Color(0xFF7C4DFF), // Purple
                        onClick = { context.startActivity(Intent(context, AppSpecificActivity::class.java)) }
                    )
                )
            }

            val horizontalPad = when (windowWidthSize) {
                WindowWidthSize.Expanded -> 32.dp
                WindowWidthSize.Medium -> 24.dp
                else -> 16.dp
            }

            // Animate the whole content (header + list) as a group for smoother entrance
            var contentVisible by remember { mutableStateOf(false) }
            // Add a small delay before showing the group content for a more relaxed feel
            LaunchedEffect(Unit) {
                delay(100L)
                contentVisible = true
            }
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(tween(500)) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) { it / 3 }
            ) {
                if (tablet) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 20.dp, bottom = 24.dp, start = horizontalPad, end = horizontalPad),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Automations",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Choose a trigger type to get started.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        gridItemsIndexed(triggerItems) { index, item ->
                            var cardVisible by remember { mutableStateOf(false) }
                            LaunchedEffect(contentVisible) {
                                if (contentVisible) {
                                    delay(120L + 100L * index)
                                    cardVisible = true
                                }
                            }
                            AnimatedVisibility(
                                visible = cardVisible,
                                enter = fadeIn(tween(450))
                            ) {
                                FeatureCard(
                                    title = item.title,
                                    description = item.description,
                                    icon = item.icon,
                                    onClick = item.onClick,
                                    accentColor = item.accentColor
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 20.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Automations",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Choose a trigger type to get started.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        itemsIndexed(triggerItems) { index, item ->
                            // Smoother: slower stagger, longer fade
                            var cardVisible by remember { mutableStateOf(false) }
                            LaunchedEffect(contentVisible) {
                                if (contentVisible) {
                                    delay(120L + 100L * index) // 120ms initial, 100ms per card
                                    cardVisible = true
                                }
                            }
                            AnimatedVisibility(
                                visible = cardVisible,
                                enter = fadeIn(tween(450))
                            ) {
                                FeatureCard(
                                    title = item.title,
                                    description = item.description,
                                    icon = item.icon,
                                    onClick = item.onClick,
                                    accentColor = item.accentColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class TriggerItem(
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accentColor: Color,
    val onClick: () -> Unit
)

