package com.autonion.automationcompanion.features.flow_automation.ui.editor

import android.app.Application
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.flow_automation.data.FlowRepository
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionEngine
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionState
import com.autonion.automationcompanion.features.flow_automation.engine.ScreenCaptureProvider
import com.autonion.automationcompanion.features.flow_automation.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "FlowEditorViewModel"

/**
 * Editor state for the flow canvas.
 */
data class FlowEditorState(
    val graph: FlowGraph = FlowGraph(name = "New Flow"),
    val selectedNodeId: String? = null,
    val selectedEdgeId: String? = null,
    val canvasOffset: Offset = Offset.Zero,
    val canvasZoom: Float = 1f,
    val isConnecting: Boolean = false,
    val connectFromNodeId: String? = null,
    val connectFromFailurePort: Boolean = false,
    val dragConnectionEndpoint: Offset? = null, // Canvas-space endpoint for rubber-band line
    val showNodePalette: Boolean = false,
    val showNodeConfig: Boolean = false,
    val showEdgeConfig: Boolean = false,
    val isDirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

class FlowEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FlowRepository(application)
    private var screenCaptureProvider: ScreenCaptureProvider? = null

    // Bug #15 fix: Track execution state from FlowExecutionService
    // via broadcast receiver instead of orphaned local engine

    private val _state = MutableStateFlow(FlowEditorState())
    val state: StateFlow<FlowEditorState> = _state.asStateFlow()

    private val _executionState = MutableStateFlow<FlowExecutionState>(FlowExecutionState.Idle)
    val executionState: StateFlow<FlowExecutionState> = _executionState.asStateFlow()

    /** Validation errors to display before running a flow. Empty = valid. */
    private val _validationErrors = MutableStateFlow<List<String>>(emptyList())
    val validationErrors: StateFlow<List<String>> = _validationErrors.asStateFlow()

    fun clearValidationErrors() { _validationErrors.value = emptyList() }

    // Bug #15 fix: Broadcast receiver for execution state updates from FlowExecutionService.
    // MUST be declared before init{} to avoid null during registration (Kotlin init order).
    private val serviceStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            intent ?: return
            when (intent.action) {
                "com.autonion.automationcompanion.flow.STATE_RUNNING" -> {
                    val nodeId = intent.getStringExtra("node_id") ?: ""
                    val nodeLabel = intent.getStringExtra("node_label") ?: ""
                    _executionState.value = FlowExecutionState.Running(nodeId, nodeLabel)
                }
                "com.autonion.automationcompanion.flow.STATE_COMPLETED" -> {
                    _executionState.value = FlowExecutionState.Completed
                }
                "com.autonion.automationcompanion.flow.STATE_ERROR" -> {
                    val msg = intent.getStringExtra("message") ?: "Unknown error"
                    _executionState.value = FlowExecutionState.Error(null, msg)
                }
                "com.autonion.automationcompanion.flow.STATE_STOPPED" -> {
                    _executionState.value = FlowExecutionState.Stopped
                }
            }
        }
    }

    init {
        // Register service state receiver
        val stateFilter = android.content.IntentFilter().apply {
            addAction("com.autonion.automationcompanion.flow.STATE_RUNNING")
            addAction("com.autonion.automationcompanion.flow.STATE_COMPLETED")
            addAction("com.autonion.automationcompanion.flow.STATE_ERROR")
            addAction("com.autonion.automationcompanion.flow.STATE_STOPPED")
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getApplication<Application>())
            .registerReceiver(serviceStateReceiver, stateFilter)
    }

    private val overlayReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            intent ?: return
            Log.d(TAG, "overlayReceiver received action: ${intent.action}")
            val action = intent.action ?: return
            val nodeId = intent.getStringExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_NODE_ID)
            val filePath = intent.getStringExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_FILE_PATH)
            Log.d(TAG, "overlayReceiver nodeId=$nodeId, filePath=$filePath")
            if (nodeId == null || filePath == null) return
            
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    Log.e(TAG, "Result file DOES NOT EXIST: $filePath")
                    return
                }
                val json = file.readText()
                Log.d(TAG, "Read json from result file length: ${json.length}")
                handleOverlayResult(nodeId, action, json, intent)
                // Clean up temp file
                file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read flow result file", e)
            }
        }
    }

    init {
        val filter = android.content.IntentFilter().apply {
            addAction(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.ACTION_FLOW_GESTURE_DONE)
            addAction(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.ACTION_FLOW_VISION_DONE)
            addAction(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.ACTION_FLOW_ML_DONE)
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(application).registerReceiver(overlayReceiver, filter)
    }

    override fun onCleared() {
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getApplication<Application>()).unregisterReceiver(overlayReceiver)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getApplication<Application>()).unregisterReceiver(serviceStateReceiver)
        screenCaptureProvider?.stop()
        super.onCleared()
    }
    


    // ─── Undo/Redo ────────────────────────────────────────────────────────

    private val undoStack = mutableListOf<FlowGraph>()
    private val redoStack = mutableListOf<FlowGraph>()
    private val maxUndoSize = 30

    private fun pushUndo() {
        val current = _state.value.graph
        undoStack.add(current)
        if (undoStack.size > maxUndoSize) undoStack.removeAt(0)
        redoStack.clear()
        _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = false) }
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(_state.value.graph)
        _state.update {
            it.copy(
                graph = previous,
                isDirty = true,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        Log.d(TAG, "Undo: stack=${undoStack.size}, redo=${redoStack.size}")
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(_state.value.graph)
        _state.update {
            it.copy(
                graph = next,
                isDirty = true,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        Log.d(TAG, "Redo: stack=${undoStack.size}, redo=${redoStack.size}")
    }

    // ─── Graph Loading ───────────────────────────────────────────────────

    fun loadFlow(flowId: String) {
        val graph = repository.load(flowId) ?: return
        _state.update { it.copy(graph = graph, isDirty = false) }
    }

    fun createNewFlow(name: String) {
        val startNode = StartNode(
            position = NodePosition(300f, 200f),
            label = "Start"
        )
        val graph = FlowGraph(
            name = name,
            nodes = listOf(startNode)
        )
        _state.update { it.copy(graph = graph, isDirty = true) }
    }

    // ─── Save ────────────────────────────────────────────────────────────

    fun saveFlow() {
        val graph = _state.value.graph
        repository.save(graph)
        _state.update { it.copy(isDirty = false) }
        Log.d(TAG, "Flow saved: ${graph.name}")
    }

    // ─── Node Operations ─────────────────────────────────────────────────

    fun addNode(type: FlowNodeType, position: NodePosition = NodePosition(400f, 400f)) {
        // Bug #14 fix: Prevent adding multiple Start nodes
        if (type == FlowNodeType.START) {
            val existingStart = _state.value.graph.findStartNode()
            if (existingStart != null) {
                Log.w(TAG, "Graph already has a Start node — cannot add another")
                android.widget.Toast.makeText(
                    getApplication<android.app.Application>(),
                    "Flow already has a Start node",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return
            }
        }
        pushUndo()
        val node: FlowNode = when (type) {
            FlowNodeType.START -> StartNode(position = position)
            FlowNodeType.GESTURE -> GestureNode(position = position)
            FlowNodeType.VISUAL_TRIGGER -> VisualTriggerNode(position = position)
            FlowNodeType.SCREEN_ML -> ScreenMLNode(position = position)
            FlowNodeType.DELAY -> DelayNode(position = position)
            FlowNodeType.LAUNCH_APP -> LaunchAppNode(position = position)
            FlowNodeType.REPEAT -> RepeatNode(position = position)
            FlowNodeType.CLIPBOARD -> ClipboardNode(position = position)
            FlowNodeType.INPUT -> InputNode(position = position)
        }
        _state.update { state ->
            state.copy(
                graph = state.graph.withNode(node),
                selectedNodeId = node.id,
                showNodePalette = false,
                showNodeConfig = true,
                isDirty = true
            )
        }
        // Warn about MediaProjection when adding LaunchApp alongside visual/ML nodes
        if (type == FlowNodeType.LAUNCH_APP) {
            val hasVisualNodes = _state.value.graph.nodes.any {
                it is VisualTriggerNode || (it is ScreenMLNode && it.needsMediaProjection())
            }
            if (hasVisualNodes) {
                val app = getApplication<android.app.Application>()
                android.widget.Toast.makeText(
                    app,
                    "\u26a0 This flow uses screen capture nodes. When running, select \"Entire screen\" in the permission dialog for app switching to work.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun deleteNode(nodeId: String) {
        pushUndo()
        _state.update { state ->
            state.copy(
                graph = state.graph.withoutNode(nodeId),
                selectedNodeId = null,
                showNodeConfig = false,
                isDirty = true
            )
        }
    }

    /**
     * Bug #9 fix: Call this at drag START (not on every move) to push undo once.
     */
    fun onDragStart(nodeId: String) {
        pushUndo()
    }

    fun updateNodePosition(nodeId: String, newPosition: NodePosition) {
        _state.update { state ->
            val node = state.graph.nodeById(nodeId) ?: return@update state
            val updatedNode = updateNodePosField(node, newPosition)
            state.copy(graph = state.graph.withNode(updatedNode), isDirty = true)
        }
    }

    fun selectNode(nodeId: String?) {
        _state.update {
            it.copy(
                selectedNodeId = nodeId,
                selectedEdgeId = null,
                showNodeConfig = nodeId != null,
                showEdgeConfig = false,
                showNodePalette = false
            )
        }
    }

    fun updateNode(node: FlowNode) {
        pushUndo()
        _state.update { state ->
            state.copy(graph = state.graph.withNode(node), isDirty = true)
        }
    }

    // ─── Edge Operations ─────────────────────────────────────────────────

    fun startConnection(fromNodeId: String) {
        _state.update { it.copy(isConnecting = true, connectFromNodeId = fromNodeId, connectFromFailurePort = false, dragConnectionEndpoint = null) }
    }

    fun startFailureConnection(fromNodeId: String) {
        _state.update { it.copy(isConnecting = true, connectFromNodeId = fromNodeId, connectFromFailurePort = true, dragConnectionEndpoint = null) }
    }

    fun updateDragEndpoint(canvasPos: Offset?) {
        _state.update { it.copy(dragConnectionEndpoint = canvasPos) }
    }

    fun completeConnection(toNodeId: String) {
        val currentState = _state.value
        val fromId = currentState.connectFromNodeId ?: return
        val isFailure = currentState.connectFromFailurePort

        // Bug #8 fix: Prevent self-connections (infinite execution loop)
        if (fromId == toNodeId) {
            Log.w(TAG, "Self-connection not allowed — would cause infinite loop")
            cancelConnection()
            return
        }

        // Prevent duplicate edges
        val existingEdge = currentState.graph.edges.find {
            it.fromNodeId == fromId && it.toNodeId == toNodeId
        }
        if (existingEdge != null) {
            cancelConnection()
            return
        }

        // Only one failure edge per node
        if (isFailure) {
            val existingFailure = currentState.graph.failureEdge(fromId)
            if (existingFailure != null) {
                Log.w(TAG, "Node $fromId already has a failure edge, replacing it")
                pushUndo()
                val cleaned = currentState.graph.withoutEdge(existingFailure.id)
                val edge = FlowEdge(
                    fromNodeId = fromId,
                    toNodeId = toNodeId,
                    isFailurePath = true
                )
                _state.update {
                    it.copy(
                        graph = cleaned.withEdge(edge),
                        isConnecting = false,
                        connectFromNodeId = null,
                        connectFromFailurePort = false,
                        dragConnectionEndpoint = null,
                        isDirty = true
                    )
                }
                return
            }
        }

        pushUndo()
        val edge = FlowEdge(
            fromNodeId = fromId,
            toNodeId = toNodeId,
            isFailurePath = isFailure
        )
        _state.update { state ->
            state.copy(
                graph = state.graph.withEdge(edge),
                isConnecting = false,
                connectFromNodeId = null,
                connectFromFailurePort = false,
                dragConnectionEndpoint = null,
                isDirty = true
            )
        }
    }

    fun cancelConnection() {
        _state.update { it.copy(isConnecting = false, connectFromNodeId = null, connectFromFailurePort = false, dragConnectionEndpoint = null) }
    }

    fun selectEdge(edgeId: String?) {
        _state.update {
            it.copy(
                selectedEdgeId = edgeId,
                selectedNodeId = null,
                showEdgeConfig = edgeId != null,
                showNodeConfig = false,
                showNodePalette = false
            )
        }
    }

    fun updateEdge(edge: FlowEdge) {
        pushUndo()
        _state.update { state ->
            state.copy(graph = state.graph.withEdge(edge), isDirty = true)
        }
    }

    fun deleteEdge(edgeId: String) {
        pushUndo()
        _state.update { state ->
            state.copy(
                graph = state.graph.withoutEdge(edgeId),
                selectedEdgeId = null,
                showEdgeConfig = false,
                isDirty = true
            )
        }
    }

    // ─── Canvas ──────────────────────────────────────────────────────────

    fun updateCanvasTransform(offset: Offset, zoom: Float) {
        _state.update { it.copy(canvasOffset = offset, canvasZoom = zoom) }
    }

    fun toggleNodePalette() {
        _state.update {
            val willShow = !it.showNodePalette
            it.copy(
                showNodePalette = willShow,
                showNodeConfig = if (willShow) false else it.showNodeConfig,
                showEdgeConfig = if (willShow) false else it.showEdgeConfig
            )
        }
    }

    fun dismissNodeConfig() {
        _state.update { it.copy(showNodeConfig = false) }
    }

    fun dismissEdgeConfig() {
        _state.update { it.copy(showEdgeConfig = false) }
    }

    fun renameFlow(newName: String) {
        _state.update { it.copy(graph = it.graph.copy(name = newName), isDirty = true) }
    }

    // ─── Execution ───────────────────────────────────────────────────────

    /**
     * Validate the flow graph before execution.
     * Returns a list of human-readable error messages. Empty list = valid.
     */
    fun validateFlow(): List<String> {
        val graph = _state.value.graph
        val errors = mutableListOf<String>()

        // 1. Must have a start node
        val start = graph.findStartNode()
        if (start == null) {
            errors.add("Flow is missing a Start node.")
            return errors
        }

        // 2. Must have at least one edge from Start
        val reachable = graph.reachableNodes()
        if (reachable.size <= 1) {
            errors.add("Flow has no nodes connected to Start. Add and connect at least one node.")
            return errors
        }

        // 3. Validate each reachable node for missing configuration
        for (node in reachable) {
            when (node) {
                is LaunchAppNode -> {
                    if (node.appPackageName.isBlank()) {
                        errors.add("\"${node.label}\" — No app selected.")
                    }
                }
                is ScreenMLNode -> {
                    if (node.automationStepsJson.isBlank()) {
                        errors.add("\"${node.label}\" — No screen data captured. Open the overlay and select elements.")
                    }
                }
                is VisualTriggerNode -> {
                    if (node.visionPresetJson.isBlank() && node.templateImagePath.isBlank()) {
                        errors.add("\"${node.label}\" — No image template captured. Open the overlay and configure regions.")
                    }
                }
                is GestureNode -> {
                    if (node.recordedActionsJson.isBlank()) {
                        errors.add("\"${node.label}\" — No gesture recorded. Open the overlay and record a gesture.")
                    }
                }
                is InputNode -> {
                    val source = node.inputSource
                    if (source is InputSource.Static && source.text.isBlank()) {
                        errors.add("\"${node.label}\" — No input text provided.")
                    }
                }
                // StartNode, DelayNode, RepeatNode, ClipboardNode are always valid
                else -> { /* no validation needed */ }
            }
        }

        return errors
    }

    fun executeFlow(resultCode: Int? = null, resultData: android.content.Intent? = null) {
        // Validate first
        val errors = validateFlow()
        if (errors.isNotEmpty()) {
            _validationErrors.value = errors
            return
        }

        saveFlow()
        
        val context = getApplication<android.app.Application>()
        val flowId = _state.value.graph.id

        // Check if any reachable node in the flow needs MediaProjection (screen capture)
        // Only nodes connected to the StartNode via edges are considered
        val needsMediaProjection = _state.value.graph.reachableNodes().any {
            it is VisualTriggerNode || (it is ScreenMLNode && it.needsMediaProjection())
        }

        if (!needsMediaProjection) {
            // No visual/ML nodes → start FlowExecutionService directly (no MP permission needed)
            val serviceIntent = com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionService.createIntent(
                context = context,
                flowId = flowId
            )
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        } else if (resultCode != null && resultData != null) {
            // Already have MP consent (passed from the projection launcher) → start service with it
            val serviceIntent = com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionService.createIntent(
                context = context,
                flowId = flowId,
                resultCode = resultCode,
                resultData = resultData
            )
            androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            // Needs MP but no consent yet → go via FlowMediaProjectionActivity to get consent
            val runIntent = android.content.Intent(context, com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity::class.java).apply {
                action = com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.ACTION_RUN_FLOW
                putExtra(com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.EXTRA_FLOW_ID, flowId)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(runIntent)
        }
    }

    fun stopExecution() {
        val context = getApplication<android.app.Application>()
        val stopIntent = android.content.Intent(context, com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionService::class.java).apply {
            action = "com.autonion.automationcompanion.flow.STOP"
        }
        context.startService(stopIntent)
    }

    // ─── Flow Mode Overlay Handling ─────────────────────────────────────

    private fun handleOverlayResult(nodeId: String, action: String, json: String, intent: android.content.Intent) {
        _state.update { state ->
            val node = state.graph.nodeById(nodeId) ?: return@update state
            val updatedNode = when (action) {
                com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.ACTION_FLOW_GESTURE_DONE -> {
                    var updated = (node as? GestureNode)?.copy(recordedActionsJson = json) ?: node
                    if (updated is GestureNode) {
                        try {
                            val actions = kotlinx.serialization.json.Json.decodeFromString<List<com.autonion.automationcompanion.features.gesture_recording_playback.models.Action>>(json)
                            val firstAction = actions.firstOrNull { it.points.isNotEmpty() }
                            if (firstAction != null) {
                                val pt = firstAction.points.first()
                                val newGestureType = when (firstAction.type) {
                                    com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.CLICK -> GestureType.TAP
                                    com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.LONG_CLICK -> GestureType.LONG_PRESS
                                    com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.SWIPE -> GestureType.SWIPE
                                    else -> GestureType.TAP
                                }
                                val swipeEndX = if (firstAction.type == com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.SWIPE) firstAction.points.lastOrNull()?.x else null
                                val swipeEndY = if (firstAction.type == com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.SWIPE) firstAction.points.lastOrNull()?.y else null
                                
                                updated = updated.copy(
                                    coordinateSource = CoordinateSource.Static(pt.x, pt.y),
                                    gestureType = newGestureType,
                                    durationMs = firstAction.duration,
                                    swipeEndX = swipeEndX,
                                    swipeEndY = swipeEndY
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed parsing gesture JSON for fallback updates", e)
                        }
                    }
                    updated
                }
                com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.ACTION_FLOW_VISION_DONE -> {
                    val imgPath = intent.getStringExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_IMAGE_PATH) ?: ""
                    (node as? VisualTriggerNode)?.copy(visionPresetJson = json, templateImagePath = imgPath) ?: node
                }
                com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.ACTION_FLOW_ML_DONE -> {
                    val imgPath = intent.getStringExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_IMAGE_PATH) ?: ""
                    // Read which tab (Elements/Text/A11yOnly) was active in the Screen ML editor
                    val editorMode = intent.getStringExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_RESULT_ML_MODE)
                    val mlMode = when (editorMode) {
                        "TEXT" -> ScreenMLMode.OCR
                        "A11Y_ONLY" -> ScreenMLMode.UI_ATTRIBUTE
                        else -> ScreenMLMode.OBJECT_DETECTION
                    }
                    (node as? ScreenMLNode)?.copy(
                        automationStepsJson = json,
                        captureImagePath = imgPath,
                        mode = mlMode
                    ) ?: node
                }
                else -> node
            }
            val newGraph = state.graph.withNode(updatedNode)
            // Save immediately updated node
            repository.save(newGraph)
            state.copy(graph = newGraph, isDirty = false)
        }
    }

    fun launchOverlayForNode(node: FlowNode) {
        val app = getApplication<Application>()
        
        when (node) {
            is GestureNode -> {
                val intent = android.content.Intent()
                intent.setClass(app, com.autonion.automationcompanion.features.gesture_recording_playback.overlay.OverlayService::class.java)
                intent.action = "com.autonion.ACTION_START_OVERLAY"
                intent.putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_MODE, true)
                intent.putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_NODE_ID, node.id)
                if (node.recordedActionsJson.isNotEmpty()) {
                    intent.putExtra("EXTRA_FLOW_GESTURE_JSON", node.recordedActionsJson)
                } else {
                    intent.putExtra("EXTRA_CLEAR_ON_START", true)
                }
                app.startService(intent)
                
                val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_HOME)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                app.startActivity(homeIntent)
            }
            is VisualTriggerNode -> {
                val intent = android.content.Intent(app, com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity::class.java).apply {
                    action = com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.ACTION_START_VISUAL_OVERLAY
                    putExtra(com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.EXTRA_NODE_ID, node.id)
                    if (node.visionPresetJson.isNotEmpty()) {
                        putExtra("EXTRA_FLOW_VISION_JSON", node.visionPresetJson)
                    } else {
                        putExtra("EXTRA_CLEAR_ON_START", true)
                    }
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                app.startActivity(intent)
            }
            is ScreenMLNode -> {
                val intent = android.content.Intent(app, com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity::class.java).apply {
                    action = com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.ACTION_START_SCREEN_ML
                    putExtra(com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.EXTRA_NODE_ID, node.id)
                    putExtra("EXTRA_FLOW_NODE_MODE", node.mode.name)
                    if (node.automationStepsJson.isNotEmpty()) {
                        putExtra("EXTRA_FLOW_ML_JSON", node.automationStepsJson)
                    } else {
                        putExtra("EXTRA_CLEAR_ON_START", true)
                    }
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                app.startActivity(intent)
            }
            else -> return
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun updateNodePosField(node: FlowNode, pos: NodePosition): FlowNode {
        return when (node) {
            is StartNode -> node.copy(position = pos)
            is GestureNode -> node.copy(position = pos)
            is VisualTriggerNode -> node.copy(position = pos)
            is ScreenMLNode -> node.copy(position = pos)
            is DelayNode -> node.copy(position = pos)
            is LaunchAppNode -> node.copy(position = pos)
            is RepeatNode -> node.copy(position = pos)
            is ClipboardNode -> node.copy(position = pos)
            is InputNode -> node.copy(position = pos)
        }
    }
}
