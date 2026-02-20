package com.autonion.automationcompanion.features.flow_automation.ui.editor

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material3.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionState
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.FlowCanvas
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.MiniMap
import com.autonion.automationcompanion.features.flow_automation.ui.editor.panels.EdgeConditionOverlay
import com.autonion.automationcompanion.features.flow_automation.ui.editor.panels.NodeConfigPanel
import com.autonion.automationcompanion.features.flow_automation.ui.editor.panels.NodePalette

/**
 * Main flow editor screen composable.
 * Combines the infinite canvas with overlaid panels for node/edge config,
 * a MiniMap for navigation, and undo/redo controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowEditorScreen(
    viewModel: FlowEditorViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val execState by viewModel.executionState.collectAsState()
    val isExecuting = execState is FlowExecutionState.Running

    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.executeFlow(result.resultCode, result.data)
        } else {
            android.widget.Toast.makeText(context, "Screen record permission is needed to run Vision and AI nodes", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101216))
            .onSizeChanged { size ->
                with(density) {
                    canvasSize = Size(size.width.toFloat(), size.height.toFloat())
                }
            }
    ) {
        // Canvas (full screen)
        FlowCanvas(
            state = state,
            executionState = execState,
            onCanvasTransform = { offset, zoom -> viewModel.updateCanvasTransform(offset, zoom) },
            onNodeTap = { viewModel.selectNode(it) },
            onNodeDrag = { id, pos -> viewModel.updateNodePosition(id, pos) },
            onEdgeTap = { viewModel.selectEdge(it) },
            onCanvasTap = {
                viewModel.selectNode(null)
                viewModel.selectEdge(null)
                viewModel.cancelConnection()
            },
            onOutputPortTap = { viewModel.startConnection(it) },
            onNodeDropForConnection = { viewModel.completeConnection(it) }
        )

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (state.isDirty) viewModel.saveFlow()
                onBack()
            }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Text(
                state.graph.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )

            // Undo / Redo
            IconButton(
                onClick = { viewModel.undo() },
                enabled = state.canUndo
            ) {
                Icon(
                    Icons.Default.Undo,
                    contentDescription = "Undo",
                    tint = if (state.canUndo) Color.White else Color.White.copy(alpha = 0.25f)
                )
            }
            IconButton(
                onClick = { viewModel.redo() },
                enabled = state.canRedo
            ) {
                Icon(
                    Icons.Default.Redo,
                    contentDescription = "Redo",
                    tint = if (state.canRedo) Color.White else Color.White.copy(alpha = 0.25f)
                )
            }

            // Node count badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.1f)
            ) {
                Text(
                    "${state.graph.nodes.size} nodes",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Save button
            TextButton(onClick = { viewModel.saveFlow() }) {
                Text(
                    if (state.isDirty) "Save •" else "Saved",
                    color = if (state.isDirty) Color(0xFF64FFDA) else Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
        }

        // MiniMap (bottom-left corner)
        MiniMap(
            state = state,
            canvasSize = canvasSize,
            onNavigate = { center ->
                val newOffset = androidx.compose.ui.geometry.Offset(
                    -center.x * state.canvasZoom + canvasSize.width / 2f,
                    -center.y * state.canvasZoom + canvasSize.height / 2f
                )
                viewModel.updateCanvasTransform(newOffset, state.canvasZoom)
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp)
                .navigationBarsPadding()
        )

        // Connection mode indicator
        AnimatedVisibility(
            visible = state.isConnecting,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF64FFDA).copy(alpha = 0.9f)
            ) {
                Text(
                    "Tap a node to connect",
                    color = Color.Black,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontSize = 13.sp
                )
            }
        }

        // Execution status bar
        if (isExecuting) {
            val runState = execState as FlowExecutionState.Running
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF2E7D32).copy(alpha = 0.9f)
            ) {
                Text(
                    "▶ ${runState.currentNodeLabel}",
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontSize = 13.sp
                )
            }
        }

        // Bottom panels
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            // Node palette
            AnimatedVisibility(
                visible = state.showNodePalette,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                NodePalette(
                    onAddNode = { type -> viewModel.addNode(type) },
                    onDismiss = { viewModel.toggleNodePalette() }
                )
            }

            // Node config panel
            AnimatedVisibility(
                visible = state.showNodeConfig && state.selectedNodeId != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                val selectedNode = state.selectedNodeId?.let { state.graph.nodeById(it) }
                if (selectedNode != null) {
                    NodeConfigPanel(
                        node = selectedNode,
                        onUpdateNode = { viewModel.updateNode(it) },
                        onDeleteNode = {
                            viewModel.deleteNode(selectedNode.id)
                        },
                        onLaunchOverlay = { node ->
                            viewModel.launchOverlayForNode(node)
                        },
                        onDismiss = { viewModel.dismissNodeConfig() }
                    )
                }
            }

            // Edge config overlay
            AnimatedVisibility(
                visible = state.showEdgeConfig && state.selectedEdgeId != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                val selectedEdge = state.selectedEdgeId?.let { state.graph.edgeById(it) }
                if (selectedEdge != null) {
                    EdgeConditionOverlay(
                        edge = selectedEdge,
                        onUpdateEdge = { viewModel.updateEdge(it) },
                        onDeleteEdge = {
                            viewModel.deleteEdge(selectedEdge.id)
                        },
                        onDismiss = { viewModel.dismissEdgeConfig() }
                    )
                }
            }
        }

        // FAB cluster (bottom-right)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Execute / Stop
            FloatingActionButton(
                onClick = {
                    if (isExecuting) {
                        viewModel.stopExecution()
                    } else {
                        if (!com.autonion.automationcompanion.AccessibilityRouter.isServiceConnected()) {
                            android.widget.Toast.makeText(context, "Please enable Accessibility Service to run flows", android.widget.Toast.LENGTH_SHORT).show()
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } else {
                            val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                            projectionLauncher.launch(mpManager.createScreenCaptureIntent())
                        }
                    }
                },
                containerColor = if (isExecuting) Color(0xFFEF5350) else Color(0xFF2E7D32),
                shape = CircleShape
            ) {
                Icon(
                    if (isExecuting) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isExecuting) "Stop" else "Run",
                    tint = Color.White
                )
            }

            // Add node
            FloatingActionButton(
                onClick = { 
                    if (!com.autonion.automationcompanion.AccessibilityRouter.isServiceConnected()) {
                        android.widget.Toast.makeText(context, "Please enable Accessibility Service to add nodes", android.widget.Toast.LENGTH_SHORT).show()
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        viewModel.toggleNodePalette() 
                    }
                },
                containerColor = Color(0xFF7B1FA2),
                shape = CircleShape
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Node", tint = Color.White)
            }
        }
    }
}
