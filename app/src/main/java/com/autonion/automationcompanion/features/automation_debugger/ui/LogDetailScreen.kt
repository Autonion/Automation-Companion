@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.features.automation_debugger.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.automation_debugger.ALL_CATEGORIES
import com.autonion.automationcompanion.features.automation_debugger.DebuggerViewModel
import com.autonion.automationcompanion.features.automation_debugger.data.ExecutionLog
import com.autonion.automationcompanion.features.automation_debugger.data.LogLevel
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@Composable
fun LogDetailScreen(
    viewModel: DebuggerViewModel,
    category: String,
    onBack: () -> Unit
) {
    val logs by viewModel.logsForCategory.collectAsState()
    val selectedLevel by viewModel.selectedLevel.collectAsState()
    val isDark = isSystemInDarkTheme()
    var showClearDialog by remember { mutableStateOf(false) }

    val categoryInfo = ALL_CATEGORIES.find { it.category == category }
    val accentColor = categoryInfo?.accentColor ?: MaterialTheme.colorScheme.primary

    // Set category on entry
    LaunchedEffect(category) {
        viewModel.selectCategory(category)
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Logs") },
            text = { Text("Clear all logs for ${categoryInfo?.displayName ?: "this category"}?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCategory(category)
                    showClearDialog = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (categoryInfo != null) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(
                                            accentColor.copy(alpha = if (isDark) 0.2f else 0.12f),
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        categoryInfo.icon,
                                        contentDescription = null,
                                        tint = accentColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                            }
                            Text(
                                categoryInfo?.displayName ?: "Logs",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        if (logs.isNotEmpty()) {
                            IconButton(onClick = { showClearDialog = true }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // ── Filter chips ──
                FilterChipRow(
                    selectedLevel = selectedLevel,
                    accentColor = accentColor,
                    onLevelSelected = { viewModel.filterByLevel(it) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (logs.isEmpty()) {
                    EmptyLogState(categoryInfo?.displayName ?: "this category", accentColor)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        itemsIndexed(logs) { index, log ->
                            val animAlpha = remember { Animatable(0f) }
                            val slide = remember { Animatable(20f) }
                            LaunchedEffect(log.id) {
                                delay((index * 30L).coerceAtMost(200L))
                                launch { animAlpha.animateTo(1f, tween(300)) }
                                launch { slide.animateTo(0f, tween(300)) }
                            }

                            Box(
                                modifier = Modifier.graphicsLayer {
                                    alpha = animAlpha.value
                                    translationY = slide.value
                                }
                            ) {
                                LogEntryCard(log = log, isDark = isDark)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// Filter Chips
// ══════════════════════════════════════════════════════════

private data class FilterOption(val label: String, val level: String?, val color: Color)

private val FILTERS = listOf(
    FilterOption("All", null, Color.Gray),
    FilterOption("Success", LogLevel.SUCCESS, Color(0xFF00C853)),
    FilterOption("Error", LogLevel.ERROR, Color(0xFFFF1744)),
    FilterOption("Warning", LogLevel.WARNING, Color(0xFFFFAB00)),
    FilterOption("Info", LogLevel.INFO, Color(0xFF2979FF))
)

@Composable
private fun FilterChipRow(
    selectedLevel: String?,
    accentColor: Color,
    onLevelSelected: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(FILTERS.size) { index ->
            val filter = FILTERS[index]
            val isSelected = selectedLevel == filter.level
            val chipBg = if (isSelected) filter.color.copy(alpha = 0.15f) else Color.Transparent
            val chipBorder = if (isSelected) filter.color.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

            Surface(
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(16.dp),
                color = chipBg,
                border = BorderStroke(1.dp, chipBorder),
                onClick = { onLevelSelected(filter.level) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (filter.level != null) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(filter.color, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        filter.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) filter.color else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// Log Entry Card
// ══════════════════════════════════════════════════════════

@Composable
private fun LogEntryCard(log: ExecutionLog, isDark: Boolean) {
    var isExpanded by remember { mutableStateOf(false) }

    val statusColor = when (log.level) {
        LogLevel.SUCCESS -> Color(0xFF00C853)
        LogLevel.ERROR -> Color(0xFFFF1744)
        LogLevel.WARNING -> Color(0xFFFFAB00)
        else -> Color(0xFF2979FF)
    }
    val statusIcon = when (log.level) {
        LogLevel.SUCCESS -> Icons.Default.CheckCircle
        LogLevel.ERROR -> Icons.Default.Error
        LogLevel.WARNING -> Icons.Default.Warning
        else -> Icons.Default.Info
    }

    val cardBg = if (isDark) Color(0xFF1E2128) else Color.White

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .animateContentSize(animationSpec = tween(200))
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 1.dp),
        border = if (isDark) BorderStroke(1.dp, statusColor.copy(alpha = 0.1f)) else null
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status dot
                Icon(
                    statusIcon,
                    contentDescription = log.level,
                    tint = statusColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))

                // Title
                Text(
                    log.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Relative timestamp
                Text(
                    formatRelativeTime(log.timestamp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 10.sp
                    )
                )
            }

            // Expanded details
            if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Divider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    thickness = 0.5.dp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    log.message,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Source tag
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            log.source,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    // Full timestamp
                    Text(
                        formatFullTimestamp(log.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }

                // Metadata if present
                if (!log.metadata.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDark) Color(0xFF15171C) else Color(0xFFF8F8FA)
                    ) {
                        Text(
                            log.metadata,
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// Empty state
// ══════════════════════════════════════════════════════════

@Composable
private fun EmptyLogState(categoryName: String, accentColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        accentColor.copy(alpha = 0.08f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Inbox,
                    contentDescription = null,
                    tint = accentColor.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No logs yet",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Logs will appear here when\n$categoryName runs",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 18.sp
                )
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
// Time formatting utilities
// ══════════════════════════════════════════════════════════

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        diff < TimeUnit.DAYS.toMillis(2) -> "Yesterday"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
    }
}

private fun formatFullTimestamp(timestamp: Long): String {
    return SimpleDateFormat("MMM dd, hh:mm:ss a", Locale.getDefault()).format(Date(timestamp))
}
