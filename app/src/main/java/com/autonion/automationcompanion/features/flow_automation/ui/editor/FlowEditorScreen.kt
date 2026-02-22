package com.autonion.automationcompanion.features.flow_automation.ui.editor

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionState
import com.autonion.automationcompanion.features.flow_automation.model.LaunchAppNode
import com.autonion.automationcompanion.features.flow_automation.model.ScreenMLNode
import com.autonion.automationcompanion.features.flow_automation.model.VisualTriggerNode
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.FlowCanvas
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.MiniMap
import com.autonion.automationcompanion.features.flow_automation.ui.editor.panels.EdgeConditionOverlay
import com.autonion.automationcompanion.features.flow_automation.ui.editor.panels.NodeConfigPanel
import com.autonion.automationcompanion.features.flow_automation.ui.editor.panels.NodePalette
import kotlinx.coroutines.delay

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

    // ── MiniMap auto-hide state ──
    var showMiniMap by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }

    // Auto-hide MiniMap after 2 seconds of canvas inactivity
    LaunchedEffect(lastInteractionTime) {
        if (lastInteractionTime > 0) {
            delay(2500)
            showMiniMap = false
        }
    }

    // ── MediaProjection blocking dialog ──
    var showFullScreenDialog by remember { mutableStateOf(false) }
    var pendingExecute by remember { mutableStateOf(false) }

    // Detect if flow has LaunchApp + visual/ML nodes (needs full-screen capture)
    val hasLaunchAppNode = remember(state.graph.nodes) {
        state.graph.nodes.any { it is LaunchAppNode }
    }
    val hasVisualNodes = remember(state.graph.nodes) {
        state.graph.nodes.any { it is VisualTriggerNode || it is ScreenMLNode }
    }
    val needsFullScreen = hasLaunchAppNode && hasVisualNodes

    // ── Warning banner for flows that need full-screen capture ──
    val showScreenCaptureWarning = remember(state.graph.nodes) {
        needsFullScreen
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.executeFlow(result.resultCode, result.data)
        } else {
            android.widget.Toast.makeText(context, "Screen record permission is needed to run Vision and AI nodes", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ── BackHandler: dismiss panels before exiting ──
    val hasPanelOpen = state.showEdgeConfig || state.showNodeConfig || state.showNodePalette
    BackHandler(enabled = hasPanelOpen) {
        when {
            state.showEdgeConfig -> viewModel.dismissEdgeConfig()
            state.showNodeConfig -> viewModel.dismissNodeConfig()
            state.showNodePalette -> viewModel.toggleNodePalette()
        }
        viewModel.selectNode(null)
        viewModel.selectEdge(null)
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
            onCanvasTransform = { offset, zoom ->
                viewModel.updateCanvasTransform(offset, zoom)
                // Show MiniMap on interaction
                showMiniMap = true
                lastInteractionTime = System.currentTimeMillis()
            },
            onNodeTap = { viewModel.selectNode(it) },
            onNodeDrag = { id, pos -> viewModel.updateNodePosition(id, pos) },
            onEdgeTap = { viewModel.selectEdge(it) },
            onCanvasTap = {
                viewModel.selectNode(null)
                viewModel.selectEdge(null)
                viewModel.cancelConnection()
            },
            onOutputPortTap = { viewModel.startConnection(it) },
            onFailurePortTap = { viewModel.startFailureConnection(it) },
            onNodeDropForConnection = { viewModel.completeConnection(it) },
            onDragEndpoint = { viewModel.updateDragEndpoint(it) },
            onEdgeCut = { edgeId -> viewModel.deleteEdge(edgeId) }
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

            // Editable flow title
            var titleText by remember(state.graph.id) { mutableStateOf(state.graph.name) }
            BasicTextField(
                value = titleText,
                onValueChange = {
                    titleText = it
                    viewModel.renameFlow(it)
                },
                textStyle = TextStyle(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                ),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                decorationBox = { innerTextField ->
                    if (titleText.isEmpty()) {
                        Text("Untitled Flow", color = Color.White.copy(0.3f), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    innerTextField()
                }
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

        // Screen capture warning banner (when flow has LaunchApp + visual nodes)
        AnimatedVisibility(
            visible = showScreenCaptureWarning && !isExecuting,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 52.dp),
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFE65100).copy(alpha = 0.9f),
                modifier = Modifier.padding(horizontal = 48.dp)
            ) {
                Text(
                    "⚠ This flow switches apps — use \"Entire screen\" when granting capture permission",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // MiniMap (bottom-left corner) — auto-hides when not navigating
        AnimatedVisibility(
            visible = showMiniMap,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp)
                .navigationBarsPadding(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            MiniMap(
                state = state,
                canvasSize = canvasSize,
                onNavigate = { center ->
                    val newOffset = androidx.compose.ui.geometry.Offset(
                        -center.x * state.canvasZoom + canvasSize.width / 2f,
                        -center.y * state.canvasZoom + canvasSize.height / 2f
                    )
                    viewModel.updateCanvasTransform(newOffset, state.canvasZoom)
                }
            )
        }

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
                        } else if (!hasVisualNodes) {
                            // No visual/ML nodes → execute directly without MediaProjection
                            viewModel.executeFlow()
                        } else if (needsFullScreen) {
                            // Has LaunchApp + visual nodes → show blocking dialog first
                            showFullScreenDialog = true
                        } else {
                            // Has visual nodes but no LaunchApp → request MP permission normally
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

    // ── Full-screen MediaProjection blocking dialog ──
    if (showFullScreenDialog) {
        AlertDialog(
            onDismissRequest = { showFullScreenDialog = false },
            containerColor = Color(0xFF1A1C1E),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f),
            title = {
                Text("⚠ Full Screen Capture Required", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This flow contains a \"Launch App\" node along with screen capture nodes (Image Match / Screen ML)."
                    )
                    Text(
                        "When the permission dialog appears, you MUST select \"Entire screen\" instead of a single app. " +
                        "Otherwise, screen capture will stop working after the app switches."
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE65100).copy(alpha = 0.15f)
                    ) {
                        Text(
                            "If single-app sharing is selected, the flow will continue running but visual/ML nodes may fail after the app switch. " +
                            "The flow will follow failure edges instead of crashing.",
                            color = Color(0xFFFFA726),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFullScreenDialog = false
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                        projectionLauncher.launch(mpManager.createScreenCaptureIntent())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("I understand — proceed", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFullScreenDialog = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}
