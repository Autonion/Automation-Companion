package com.autonion.automationcompanion.features.flow_automation.ui.list

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.NodeColors
import com.autonion.automationcompanion.ui.components.AuroraBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flow list screen — shows saved flows with edit, run, export, and delete actions.
 * Supports importing flows from external JSON files.
 * Uses AuroraBackground + MaterialTheme for light/dark support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowListScreen(
    viewModel: FlowListViewModel,
    onCreateNew: () -> Unit,
    onEditFlow: (String) -> Unit,
    onRunFlow: (String) -> Unit,
    onBack: () -> Unit
) {
    val flows by viewModel.flows.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDark = isSystemInDarkTheme()

    // Import picker
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importFlow(uri)
        }
    }

    // Export picker — needs to know which flow to export
    var exportingFlowId by remember { mutableStateOf<String?>(null) }
    var exportingFlowName by remember { mutableStateOf("flow") }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val flowId = exportingFlowId
        if (uri != null && flowId != null) {
            viewModel.exportFlow(flowId, uri)
        }
        exportingFlowId = null
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Flow Builder",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    },
                    actions = {
                        // Import button
                        IconButton(onClick = {
                            importLauncher.launch(arrayOf("application/json"))
                        }) {
                            Icon(
                                Icons.Default.FileOpen,
                                contentDescription = "Import Flow",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
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
            floatingActionButton = {
                if (flows.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            if (!com.autonion.automationcompanion.AccessibilityRouter.isServiceConnected()) {
                                android.widget.Toast.makeText(context, "Please enable Accessibility Service to create flows", android.widget.Toast.LENGTH_SHORT).show()
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } else {
                                onCreateNew()
                            }
                        },
                        containerColor = NodeColors.VisualTriggerPurple,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Flow", tint = Color.White)
                    }
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (flows.isEmpty()) {
                    EmptyFlowState(
                        isDark = isDark,
                        onCreateNew = {
                            if (!com.autonion.automationcompanion.AccessibilityRouter.isServiceConnected()) {
                                android.widget.Toast.makeText(context, "Please enable Accessibility Service to create flows", android.widget.Toast.LENGTH_SHORT).show()
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } else {
                                onCreateNew()
                            }
                        },
                        onImport = { importLauncher.launch(arrayOf("application/json")) }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(flows, key = { it.id }) { flow ->
                            FlowCard(
                                flow = flow,
                                isDark = isDark,
                                onEdit = { onEditFlow(flow.id) },
                                onRun = { onRunFlow(flow.id) },
                                onExport = {
                                    exportingFlowId = flow.id
                                    exportingFlowName = flow.name
                                    exportLauncher.launch("${flow.name}.json")
                                },
                                onDelete = { viewModel.deleteFlow(flow.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Empty state with glowing pulsing icon — matches GestureRecording & VisualTrigger pattern.
 */
@Composable
private fun EmptyFlowState(
    isDark: Boolean,
    onCreateNew: () -> Unit,
    onImport: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "flowPulse")
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

    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Glowing icon
            Box(contentAlignment = Alignment.Center) {
                // Outer glow ring
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(NodeColors.VisualTriggerPurple.copy(alpha = pulseAlpha * 0.3f))
                )
                // Inner icon container
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(
                            NodeColors.VisualTriggerPurple.copy(
                                alpha = if (isDark) 0.2f else 0.12f
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = NodeColors.VisualTriggerPurple
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "No flows yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Create your first automation flow\nor import one from a file",
                style = MaterialTheme.typography.bodyMedium,
                color = if (isDark) Color.White.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCreateNew,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NodeColors.VisualTriggerPurple
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Create Flow")
                }
                OutlinedButton(
                    onClick = onImport,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        Icons.Default.FileOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Import", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun FlowCard(
    flow: FlowGraph,
    isDark: Boolean,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val cardBg = if (isDark) Color(0xFF1A1C1E) else MaterialTheme.colorScheme.surfaceContainer
    val textPrimary = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val textSecondary = if (isDark) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant
    val textTertiary = if (isDark) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Flow icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NodeColors.VisualTriggerPurple.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = NodeColors.VisualTriggerPurple,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        flow.name,
                        color = textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "${flow.nodes.size} nodes · ${flow.edges.size} edges",
                        color = textSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Updated: ${dateFormat.format(Date(flow.updatedAt))}",
                    color = textTertiary,
                    fontSize = 11.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Run button
                    IconButton(
                        onClick = {
                            if (!com.autonion.automationcompanion.AccessibilityRouter.isServiceConnected()) {
                                android.widget.Toast.makeText(context, "Please enable Accessibility Service to run flows", android.widget.Toast.LENGTH_SHORT).show()
                                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } else {
                                onRun()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Run",
                            tint = NodeColors.StartGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Edit button
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Export button
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Export",
                            tint = Color(0xFF90CAF9).copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Delete button
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color(0xFFEF5350).copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Delete confirmation
            if (showDeleteConfirm) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Delete this flow?", color = Color(0xFFEF5350), fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = textSecondary, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }) {
                        Text("Delete", color = Color(0xFFEF5350), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
