@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import com.autonion.automationcompanion.ui.components.*
import com.autonion.automationcompanion.ui.components.AuroraBackground
import com.autonion.automationcompanion.ui.theme.*

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val isDark = isSystemInDarkTheme()
    
    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent, // Let Aurora show through
        ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Header
            item {
                StaggeredEntry(index = 0) {
                    DashboardHeader(
                        title = "Autonion", 
                        subtitle = null,
                        onNotificationClick = null
                    )
                }
            }

            // Quick Actions - Removed as requested
            /*
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PrimaryButton(
                        text = "New Macro",
                        icon = Icons.Default.Add,
                        onClick = { /* TODO */ },
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        text = "Recent Logs",
                        icon = Icons.Default.History,
                        onClick = { /* TODO */ },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            */

            // Section Title
            item {
                StaggeredEntry(index = 1) {
                    Text(
                        "Tools & Features",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground // Explicitly set color for Dark Mode
                        ),
                        modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 16.dp)
                    )
                }
            }

            // Hero Card: Gesture Recording
            item {
                StaggeredEntry(index = 2) {
                    HeroCard(
                        title = "Gesture Recording",
                        description = "Record gestures across apps and replay as macros seamlessly.",
                        icon = Icons.Default.TouchApp,
                        iconColor = Color.White,
                        iconContainerColor = AccentPurple,
                        onClick = { onOpen(AutomationRoutes.GESTURE) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Grid Row: Screen AI & Semantic
            item {
                StaggeredEntry(index = 3) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        GridCard(
                            title = "Screen Context AI",
                            description = "Detect UI elements, OCR & contextualize screen content.",
                            icon = Icons.Default.ViewQuilt,
                            iconColor = Color.White,
                            iconContainerColor = AccentBlue,
                            onClick = { onOpen(AutomationRoutes.SCREEN_UNDERSTAND) },
                            modifier = Modifier.weight(1f)
                        )
                        GridCard(
                            title = "Semantic Automation",
                            description = "Create complex automations via natural language.",
                            icon = Icons.Default.ChatBubble,
                            iconColor = Color.White,
                            iconContainerColor = AccentGreen,
                            onClick = { onOpen(AutomationRoutes.SEMANTIC) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // List Cards: Conditional & System
            item {
                StaggeredEntry(index = 4) {
                    ListCard(
                        title = "Conditional Macros",
                        description = "Add logic, conditions and guards to your workflow.",
                        icon = Icons.Default.CallSplit,
                        iconColor = Color.White,
                        iconContainerColor = AccentOrange,
                        onClick = { onOpen(AutomationRoutes.CONDITIONAL) }
                    )
                }
            }
            item {
                StaggeredEntry(index = 5) {
                    ListCard(
                        title = "System Context",
                        description = "Triggers based on location, time, battery level.",
                        icon = Icons.Default.SettingsSystemDaydream,
                        iconColor = Color.White,
                        iconContainerColor = AccentBlue, // Reusing Blue or maybe different shade
                        onClick = { onOpen(AutomationRoutes.SYSTEM_CONTEXT) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Grid Row: Emergency & Debugger
            item {
                StaggeredEntry(index = 6) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Custom styling for Emergency to match screenshot (Light Red bg)
                        StatusCard(
                            title = "Emergency Trigger",
                            subtitle = "Panic gestures setup",
                            icon = Icons.Default.Warning,
                            iconColor = Color.White,
                            iconContainerColor = AccentRed,
                            backgroundColor = if (isDark) DarkAccentRedContainer else AccentRedContainer,
                            onClick = { onOpen(AutomationRoutes.EMERGENCY) },
                            modifier = Modifier.weight(1f)
                        )
                        
                        // Custom styling for Debugger (Light Grey bg)
                        StatusCard(
                            title = "Debugger",
                            subtitle = "Step through runs",
                            icon = Icons.Default.BugReport,
                            iconColor = Color.White,
                            iconContainerColor = AccentGrey,
                            backgroundColor = if (isDark) DarkAccentGreyContainer else Color.White,
                            onClick = { onOpen(AutomationRoutes.DEBUGGER) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Banner: Cross-Device
            item {
                StaggeredEntry(index = 7) {
                    BannerCard(
                        title = "Cross-Device Sync",
                        description = "Coordinate automations across ecosystem.",
                        icon = Icons.Default.Devices,
                        onClick = { onOpen(AutomationRoutes.CROSS_DEVICE) }
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
    }
}

@Preview
@Composable
fun HomeScreenPreview() {
    HomeScreen(onOpen = {})
}