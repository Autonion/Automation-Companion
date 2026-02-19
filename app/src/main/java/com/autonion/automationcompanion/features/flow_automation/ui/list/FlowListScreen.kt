package com.autonion.automationcompanion.features.flow_automation.ui.list

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.FlowGraph
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.NodeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flow list screen — shows saved flows with edit, run, and delete actions.
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

    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D0F12), Color(0xFF101216))
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
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
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White
                )
            )

            if (flows.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔀", fontSize = 64.sp)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No flows yet",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Create your first automation flow",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(24.dp))
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
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(flows, key = { it.id }) { flow ->
                        FlowCard(
                            flow = flow,
                            onEdit = { onEditFlow(flow.id) },
                            onRun = { onRunFlow(flow.id) },
                            onDelete = { viewModel.deleteFlow(flow.id) }
                        )
                    }
                }
            }
        }

        // FAB for creating new flow
        if (flows.isNotEmpty()) {
            FloatingActionButton(
                onClick = onCreateNew,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .navigationBarsPadding(),
                containerColor = NodeColors.VisualTriggerPurple,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Flow", tint = Color.White)
            }
        }
    }
}

@Composable
private fun FlowCard(
    flow: FlowGraph,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onEdit),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1E)),
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
                    Text("🔀", fontSize = 20.sp)
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        flow.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        "${flow.nodes.size} nodes · ${flow.edges.size} edges",
                        color = Color.White.copy(alpha = 0.5f),
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
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 11.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Run button
                    IconButton(
                        onClick = onRun,
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
                            tint = Color.White.copy(alpha = 0.6f),
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
                        Text("Cancel", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
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
