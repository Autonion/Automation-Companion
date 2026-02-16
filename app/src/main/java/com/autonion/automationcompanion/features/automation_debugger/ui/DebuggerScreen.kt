@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.features.automation_debugger.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.automation_debugger.CategorySummary
import com.autonion.automationcompanion.features.automation_debugger.DebuggerViewModel
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DebuggerScreen(
    viewModel: DebuggerViewModel,
    onBack: () -> Unit,
    onCategoryClick: (String) -> Unit
) {
    val summaries by viewModel.categorySummaries.collectAsState()
    val totalCount by viewModel.totalLogCount.collectAsState()
    val isDark = isSystemInDarkTheme()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Logs") },
            text = { Text("This will permanently delete all execution logs across every category.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
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
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // ── Header with animated accent ──
                item {
                    DebuggerHeader(totalCount = totalCount, onClearAll = { showClearDialog = true })
                }

                // ── Live pulse indicator ──
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    LiveStatusStrip(totalCount = totalCount, isDark = isDark)
                    Spacer(modifier = Modifier.height(20.dp))
                }

                // ── Category cards ──
                itemsIndexed(summaries) { index, summary ->
                    val animAlpha = remember { Animatable(0f) }
                    val slide = remember { Animatable(40f) }
                    LaunchedEffect(Unit) {
                        delay((index * 60L).coerceAtMost(300L))
                        launch { animAlpha.animateTo(1f, tween(400, easing = FastOutSlowInEasing)) }
                        launch { slide.animateTo(0f, tween(400, easing = FastOutSlowInEasing)) }
                    }

                    Box(
                        modifier = Modifier.graphicsLayer {
                            alpha = animAlpha.value
                            translationY = slide.value
                        }
                    ) {
                        DebuggerCategoryCard(
                            summary = summary,
                            isDark = isDark,
                            onClick = { onCategoryClick(summary.category) }
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// Header
// ══════════════════════════════════════════════════════════

@Composable
private fun DebuggerHeader(totalCount: Int, onClearAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column {
            Text(
                "Debugger",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Execution logs & diagnostics",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        if (totalCount > 0) {
            IconButton(onClick = onClearAll) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = "Clear All",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// Live status strip with animated pulse
// ══════════════════════════════════════════════════════════

@Composable
private fun LiveStatusStrip(totalCount: Int, isDark: Boolean) {
    val pulseAlpha = rememberInfiniteTransition(label = "pulse")
    val alpha by pulseAlpha.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val stripBg = if (isDark) Color(0xFF1A1D24) else Color(0xFFF5F5F7)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .background(stripBg, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Animated pulse dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { this.alpha = if (totalCount > 0) alpha else 0.3f }
                    .background(
                        if (totalCount > 0) Color(0xFF00E676) else Color.Gray,
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                if (totalCount > 0) "Monitoring active" else "No logs recorded",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        ) {
            Text(
                "$totalCount",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

// ══════════════════════════════════════════════════════════
// Category Card with accent gradient edge
// ══════════════════════════════════════════════════════════

@Composable
private fun DebuggerCategoryCard(
    summary: CategorySummary,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (isPressed) 0.97f else 1f, label = "scale")

    val cardBg = if (isDark) Color(0xFF1E2128) else Color.White
    val accentFaded = summary.accentColor.copy(alpha = if (isDark) 0.15f else 0.08f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 2.dp),
        border = if (isDark) BorderStroke(1.dp, summary.accentColor.copy(alpha = 0.2f))
                 else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // Left accent bar
                    drawRect(
                        color = summary.accentColor,
                        topLeft = Offset.Zero,
                        size = androidx.compose.ui.geometry.Size(6.dp.toPx(), size.height)
                    )
                }
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(accentFaded, Color.Transparent),
                        endX = 300f
                    )
                )
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icon container
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        summary.accentColor.copy(alpha = if (isDark) 0.2f else 0.12f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    summary.icon,
                    contentDescription = null,
                    tint = summary.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Text content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summary.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    summary.description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                )
                // Latest log preview
                summary.latestLog?.let { log ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        log.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = when (log.level) {
                                "SUCCESS" -> Color(0xFF00C853)
                                "ERROR" -> Color(0xFFFF1744)
                                "WARNING" -> Color(0xFFFFAB00)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Count badge + chevron
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center
            ) {
                if (summary.logCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = summary.accentColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            "${summary.logCount}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = summary.accentColor
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
