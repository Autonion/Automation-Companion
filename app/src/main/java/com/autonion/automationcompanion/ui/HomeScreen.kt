@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import com.autonion.automationcompanion.ui.components.*
import com.autonion.automationcompanion.ui.components.AuroraBackground
import com.autonion.automationcompanion.ui.theme.*
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.AccessibilityRouter
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Composable
fun HomeScreen(
    onOpen: (String) -> Unit,
    onConnectAI: () -> Unit
) {
    val windowWidthSize = rememberWindowWidthSize()

    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
        ) { innerPadding ->
            when (windowWidthSize) {
                WindowWidthSize.Compact -> CompactHomeLayout(
                    onOpen = onOpen,
                    onConnectAI = onConnectAI,
                    innerPadding = innerPadding
                )
                WindowWidthSize.Medium -> MediumHomeLayout(
                    onOpen = onOpen,
                    innerPadding = innerPadding
                )
                WindowWidthSize.Expanded -> ExpandedHomeLayout(
                    onOpen = onOpen,
                    innerPadding = innerPadding
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  COMPACT — Phone layout (unchanged from original)
// ═══════════════════════════════════════════════════════════════

@Composable
private fun CompactHomeLayout(
    onOpen: (String) -> Unit,
    onConnectAI: () -> Unit,
    innerPadding: PaddingValues
) {
    val isDark = isSystemInDarkTheme()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        // Header
        item {
            StaggeredEntry(index = 0) {
                DashboardHeader(
                    title = "Autonion",
                    subtitle = null,
                    onNotificationClick = null,
                    onExclusionClick = { onOpen("settings/exclusion") },
                    onBackupClick = { onOpen("settings/backup_restore") }
                )
            }
        }

        // Section Title
        item {
            StaggeredEntry(index = 1) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // ── Getting Started Checklist ──
                    val context = LocalContext.current
                    val onboardingPrefs = remember { OnboardingPreferences.getInstance(context) }
                    var isDismissed by remember { mutableStateOf(onboardingPrefs.isGettingStartedDismissed) }
                    val isAIConnected = onboardingPrefs.hasConnectedAI
                    val hasCreatedAutomation = onboardingPrefs.hasCreatedFirstAutomation

                    if (!isDismissed) {
                        GettingStartedCard(
                            isAIConnected = isAIConnected,
                            hasCreatedAutomation = hasCreatedAutomation,
                            onConnectAI = onConnectAI,
                            onCreateAutomation = { onOpen(AutomationRoutes.GESTURE) },
                            onDismiss = {
                                onboardingPrefs.isGettingStartedDismissed = true
                                isDismissed = true
                            }
                        )
                    }

                    Text(
                        "Tools & Features",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        modifier = Modifier.padding(
                            start = 24.dp,
                            top = if (isDismissed) 24.dp else 12.dp,
                            bottom = 16.dp
                        )
                    )
                }
            }
        }

        // Hero Card: Gesture Recording
        item {
            StaggeredEntry(index = 3) {
                HeroCard(
                    title = "Gesture Recording",
                    description = "Record gestures across apps and replay as macros seamlessly.",
                    icon = Icons.Default.TouchApp,
                    iconColor = Color.White,
                    iconContainerColor = AccentBlue,
                    onClick = { onOpen(AutomationRoutes.GESTURE) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Grid Row: Screen AI & Visual Triggers
        item {
            StaggeredEntry(index = 4) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    GridCard(
                        title = "UI Recognition AI",
                        description = "Detect UI elements, OCR & contextualize screen content.",
                        icon = Icons.AutoMirrored.Filled.ViewQuilt,
                        iconColor = Color.White,
                        iconContainerColor = AccentBlue,
                        onClick = { onOpen(AutomationRoutes.SCREEN_UNDERSTAND) },
                        modifier = Modifier.weight(1f)
                    )
                    GridCard(
                        title = "Visual Triggers",
                        description = "Automate actions based on detected visual regions.",
                        icon = Icons.Default.Screenshot,
                        iconColor = Color.White,
                        iconContainerColor = AccentGreen,
                        onClick = { onOpen(AutomationRoutes.VISUAL_TRIGGER) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // List Cards: Flow Builder & Semantic
        item {
            StaggeredEntry(index = 5) {
                ListCard(
                    title = "Flow Builder",
                    description = "Build visual automation flows with a node-based graph editor.",
                    icon = Icons.Default.AccountTree,
                    iconColor = Color.White,
                    iconContainerColor = AccentPurple,
                    onClick = { onOpen(AutomationRoutes.FLOW_BUILDER) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        item {
            StaggeredEntry(index = 6) {
                ListCard(
                    title = "Semantic AI Agent",
                    description = "Natural language automation — describe a task and let the AI agent execute it.",
                    icon = Icons.Default.AutoAwesome,
                    iconColor = Color.White,
                    iconContainerColor = AccentOrange,
                    onClick = { onOpen(AutomationRoutes.SEMANTIC_AUTOMATION) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        // Grid Row: System Context & Debugger
        item {
            StaggeredEntry(index = 7) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusCard(
                        title = "System Context",
                        subtitle = "Location, time, battery",
                        icon = Icons.Default.SettingsSystemDaydream,
                        iconColor = Color.White,
                        iconContainerColor = AccentBlue,
                        backgroundColor = if (isDark) Color(0xFF003B5C) else AccentBlueContainer,
                        titleColor = if (isDark) MaterialTheme.colorScheme.onSurface else KeyBlue,
                        onClick = { onOpen(AutomationRoutes.SYSTEM_CONTEXT) },
                        modifier = Modifier.weight(1f)
                    )
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
            StaggeredEntry(index = 8) {
                BannerCard(
                    title = "Cross-Device Sync",
                    description = "Coordinate automations across ecosystem.",
                    icon = Icons.Default.Devices,
                    onClick = { onOpen(AutomationRoutes.CROSS_DEVICE) }
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Footer: Version & GitHub
        item {
            HomeFooter()
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  MEDIUM — Tablet portrait (2-panel: Branding + Single-column)
// ═══════════════════════════════════════════════════════════════

@Composable
private fun MediumHomeLayout(
    onOpen: (String) -> Unit,
    innerPadding: PaddingValues
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Branding Header (Full Width)
        item(span = { GridItemSpan(2) }) {
            DashboardHeader(
                title = "Autonion",
                subtitle = "AI-Powered Automation",
                onExclusionClick = { onOpen("settings/exclusion") },
                onBackupClick = { onOpen("settings/backup_restore") }
            )
        }

        // Intro Text (Full Width)
        item(span = { GridItemSpan(2) }) {
            Text(
                text = "Record gestures, build visual flows, and let AI agents automate tasks — all on-device with optional cloud power.",
                style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(bottom = 16.dp, start = 8.dp, end = 8.dp)
            )
        }

        // Feature Cards (2 Columns)
        item {
            StaggeredEntry(index = 0) {
                GridCard(
                    title = "Gesture Recording",
                    description = "Record gestures across apps and replay as macros seamlessly.",
                    icon = Icons.Default.TouchApp,
                    iconColor = Color.White,
                    iconContainerColor = AccentBlue,
                    onClick = { onOpen(AutomationRoutes.GESTURE) },
                    modifier = Modifier.fillMaxSize(),
                    titleFontSize = 20.sp,
                    descFontSize = 15.sp
                )
            }
        }
        item {
            StaggeredEntry(index = 1) {
                GridCard(
                    title = "UI Recognition AI",
                    description = "Detect UI elements, OCR & contextualize screen content.",
                    icon = Icons.AutoMirrored.Filled.ViewQuilt,
                    iconColor = Color.White,
                    iconContainerColor = AccentBlue,
                    onClick = { onOpen(AutomationRoutes.SCREEN_UNDERSTAND) },
                    modifier = Modifier.fillMaxSize(),
                    titleFontSize = 20.sp,
                    descFontSize = 15.sp
                )
            }
        }
        item {
            StaggeredEntry(index = 2) {
                GridCard(
                    title = "Visual Triggers",
                    description = "Automate actions based on detected visual regions.",
                    icon = Icons.Default.Screenshot,
                    iconColor = Color.White,
                    iconContainerColor = AccentGreen,
                    onClick = { onOpen(AutomationRoutes.VISUAL_TRIGGER) },
                    modifier = Modifier.fillMaxSize(),
                    titleFontSize = 20.sp,
                    descFontSize = 15.sp
                )
            }
        }
        item {
            StaggeredEntry(index = 3) {
                GridCard(
                    title = "Flow Builder",
                    description = "Build visual automation flows with a node-based graph editor.",
                    icon = Icons.Default.AccountTree,
                    iconColor = Color.White,
                    iconContainerColor = AccentPurple,
                    onClick = { onOpen(AutomationRoutes.FLOW_BUILDER) },
                    modifier = Modifier.fillMaxSize(),
                    titleFontSize = 20.sp,
                    descFontSize = 15.sp
                )
            }
        }
        item {
            StaggeredEntry(index = 4) {
                GridCard(
                    title = "Semantic AI Agent",
                    description = "Natural language automation — describe a task and let AI execute it.",
                    icon = Icons.Default.AutoAwesome,
                    iconColor = Color.White,
                    iconContainerColor = AccentOrange,
                    onClick = { onOpen(AutomationRoutes.SEMANTIC_AUTOMATION) },
                    modifier = Modifier.fillMaxSize(),
                    titleFontSize = 20.sp,
                    descFontSize = 15.sp
                )
            }
        }
        item {
            StaggeredEntry(index = 5) {
                GridCard(
                    title = "System Context",
                    description = "Location, time, battery-based automation triggers.",
                    icon = Icons.Default.SettingsSystemDaydream,
                    iconColor = Color.White,
                    iconContainerColor = AccentBlue,
                    onClick = { onOpen(AutomationRoutes.SYSTEM_CONTEXT) },
                    modifier = Modifier.fillMaxSize(),
                    titleFontSize = 20.sp,
                    descFontSize = 15.sp
                )
            }
        }
        item {
            StaggeredEntry(index = 6) {
                GridCard(
                    title = "Debugger",
                    description = "Step through and inspect automation runs.",
                    icon = Icons.Default.BugReport,
                    iconColor = Color.White,
                    iconContainerColor = AccentGrey,
                    onClick = { onOpen(AutomationRoutes.DEBUGGER) },
                    modifier = Modifier.fillMaxSize(),
                    titleFontSize = 20.sp,
                    descFontSize = 15.sp
                )
            }
        }

        // Banner
        item(span = { GridItemSpan(2) }) {
            Spacer(modifier = Modifier.height(8.dp))
            StaggeredEntry(index = 7) {
                BannerCard(
                    title = "Cross-Device Sync",
                    description = "Coordinate automations across ecosystem.",
                    icon = Icons.Default.Devices,
                    onClick = { onOpen(AutomationRoutes.CROSS_DEVICE) }
                )
            }
        }

        // Footer
        item(span = { GridItemSpan(2) }) {
            HomeFooter()
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  EXPANDED — Large tablet / landscape (2-panel: Branding + Grid)
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ExpandedHomeLayout(
    onOpen: (String) -> Unit,
    innerPadding: PaddingValues
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
    ) {
        // Left Panel — Branding (32%)
        TabletBrandingPanel(
            onExclusionClick = { onOpen("settings/exclusion") },
            onBackupClick = { onOpen("settings/backup_restore") },
            modifier = Modifier
                .fillMaxHeight()
                .weight(0.32f)
        )

        // Right Panel — Feature Grid (68%)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .weight(0.68f)
                .fillMaxHeight()
                .padding(end = 24.dp),
            contentPadding = PaddingValues(top = 48.dp, bottom = 32.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Section Title — spans 2 columns
            item(span = { GridItemSpan(2) }) {
                Text(
                    "Tools & Features",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // Feature cards
            item {
                StaggeredEntry(index = 0) {
                    GridCard(
                        title = "Gesture Recording",
                        description = "Record gestures across apps and replay as macros.",
                        icon = Icons.Default.TouchApp,
                        iconColor = Color.White,
                        iconContainerColor = AccentBlue,
                        onClick = { onOpen(AutomationRoutes.GESTURE) },
                        modifier = Modifier.fillMaxSize(),
                        titleFontSize = 20.sp,
                        descFontSize = 15.sp
                    )
                }
            }
            item {
                StaggeredEntry(index = 1) {
                    GridCard(
                        title = "UI Recognition AI",
                        description = "Detect UI elements, OCR & contextualize screen content.",
                        icon = Icons.AutoMirrored.Filled.ViewQuilt,
                        iconColor = Color.White,
                        iconContainerColor = AccentBlue,
                        onClick = { onOpen(AutomationRoutes.SCREEN_UNDERSTAND) },
                        modifier = Modifier.fillMaxSize(),
                        titleFontSize = 20.sp,
                        descFontSize = 15.sp
                    )
                }
            }
            item {
                StaggeredEntry(index = 2) {
                    GridCard(
                        title = "Visual Triggers",
                        description = "Automate actions based on detected visual regions.",
                        icon = Icons.Default.Screenshot,
                        iconColor = Color.White,
                        iconContainerColor = AccentGreen,
                        onClick = { onOpen(AutomationRoutes.VISUAL_TRIGGER) },
                        modifier = Modifier.fillMaxSize(),
                        titleFontSize = 20.sp,
                        descFontSize = 15.sp
                    )
                }
            }
            item {
                StaggeredEntry(index = 3) {
                    GridCard(
                        title = "Flow Builder",
                        description = "Build visual flows with a node-based graph editor.",
                        icon = Icons.Default.AccountTree,
                        iconColor = Color.White,
                        iconContainerColor = AccentPurple,
                        onClick = { onOpen(AutomationRoutes.FLOW_BUILDER) },
                        modifier = Modifier.fillMaxSize(),
                        titleFontSize = 20.sp,
                        descFontSize = 15.sp
                    )
                }
            }
            item {
                StaggeredEntry(index = 4) {
                    GridCard(
                        title = "Semantic AI Agent",
                        description = "Describe a task and let the AI agent execute it.",
                        icon = Icons.Default.AutoAwesome,
                        iconColor = Color.White,
                        iconContainerColor = AccentOrange,
                        onClick = { onOpen(AutomationRoutes.SEMANTIC_AUTOMATION) },
                        modifier = Modifier.fillMaxSize(),
                        titleFontSize = 20.sp,
                        descFontSize = 15.sp
                    )
                }
            }
            item {
                StaggeredEntry(index = 5) {
                    GridCard(
                        title = "System Context",
                        description = "Location, time, battery-based automation triggers.",
                        icon = Icons.Default.SettingsSystemDaydream,
                        iconColor = Color.White,
                        iconContainerColor = AccentBlue,
                        onClick = { onOpen(AutomationRoutes.SYSTEM_CONTEXT) },
                        modifier = Modifier.fillMaxSize(),
                        titleFontSize = 20.sp,
                        descFontSize = 15.sp
                    )
                }
            }
            item {
                StaggeredEntry(index = 6) {
                    GridCard(
                        title = "Debugger",
                        description = "Step through and inspect automation runs.",
                        icon = Icons.Default.BugReport,
                        iconColor = Color.White,
                        iconContainerColor = AccentGrey,
                        onClick = { onOpen(AutomationRoutes.DEBUGGER) },
                        modifier = Modifier.fillMaxSize(),
                        titleFontSize = 20.sp,
                        descFontSize = 15.sp
                    )
                }
            }

            // Banner — spans 2 columns
            item(span = { GridItemSpan(2) }) {
                Spacer(modifier = Modifier.height(8.dp))
                BannerCard(
                    title = "Cross-Device Sync",
                    description = "Coordinate automations across ecosystem.",
                    icon = Icons.Default.Devices,
                    onClick = { onOpen(AutomationRoutes.CROSS_DEVICE) }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Shared — Footer
// ═══════════════════════════════════════════════════════════════

@Composable
private fun HomeFooter() {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "v1.0.7",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        )
        Text(
            text = "  ·  ",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
        )
        Text(
            text = "github.com/Autonion",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                textDecoration = TextDecoration.Underline
            ),
            modifier = Modifier.clickable {
                uriHandler.openUri("https://github.com/Autonion")
            }
        )
        Text(
            text = "  ·  ",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
        )
        Text(
            text = "Privacy Policy",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                fontSize = 12.sp,
                textDecoration = TextDecoration.Underline
            ),
            modifier = Modifier.clickable {
                uriHandler.openUri("https://autonion.github.io/autonion-policies/")
            }
        )
    }
}

@Preview
@Composable
fun HomeScreenPreview() {
    HomeScreen(onOpen = {}, onConnectAI = {})
}