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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemContextMainScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("System Context Automation") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
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
                        onClick = { context.startActivity(Intent(context, LocationSlotsActivity::class.java)) }
                    ),
                    TriggerItem(
                        title = "Battery Level",
                        description = "Trigger when battery reaches threshold",
                        icon = Icons.Default.BatteryStd,
                        onClick = { context.startActivity(Intent(context, BatterySlotsActivity::class.java)) }
                    ),
                    TriggerItem(
                        title = "Time of Day",
                        description = "Trigger at specific time daily",
                        icon = Icons.Default.Schedule,
                        onClick = { context.startActivity(Intent(context, TimeOfDayActivity::class.java)) }
                    ),
                    TriggerItem(
                        title = "Wi-Fi Connectivity",
                        description = "Trigger on Wi-Fi connect/disconnect",
                        icon = Icons.Default.Wifi,
                        onClick = { context.startActivity(Intent(context, WiFiActivity::class.java)) }
                    ),
                    TriggerItem(
                        title = "App Specific",
                        description = "Trigger when app opens/closes",
                        icon = Icons.Default.Apps,
                        onClick = { context.startActivity(Intent(context, AppSpecificActivity::class.java)) }
                    )
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 20.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    var headerVisible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { headerVisible = true }
                    AnimatedVisibility(
                        visible = headerVisible,
                        enter = fadeIn(tween(500)) + slideInVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) { -it / 2 } // Slide from top slightly
                    ) {
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
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                itemsIndexed(triggerItems) { index, item ->
                    // Staggered animation state
                    val visibleState = remember {
                        MutableTransitionState(false).apply {
                            // Start invisible
                            targetState = false
                        }
                    }

                    LaunchedEffect(Unit) {
                        delay(75L * index) // Stagger delay
                        visibleState.targetState = true
                    }

                    AnimatedVisibility(
                        visibleState = visibleState,
                        enter = fadeIn(tween(400)) + slideInVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessLow
                            ) // Bouncy spring
                        ) { it / 2 } // Slide up from half height
                    ) {
                        FeatureCard(
                            title = item.title,
                            description = item.description,
                            icon = item.icon,
                            onClick = item.onClick
                        )
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
    val onClick: () -> Unit
)
