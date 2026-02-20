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
    val showNodePalette: Boolean = false,
    val showNodeConfig: Boolean = false,
    val showEdgeConfig: Boolean = false,
    val isDirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

class FlowEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FlowRepository(application)
    private var executionEngine = FlowExecutionEngine(application)
    private var screenCaptureProvider: ScreenCaptureProvider? = null

    private val _state = MutableStateFlow(FlowEditorState())
    val state: StateFlow<FlowEditorState> = _state.asStateFlow()

    private val _executionState = MutableStateFlow<FlowExecutionState>(FlowExecutionState.Idle)
    val executionState: StateFlow<FlowExecutionState> = _executionState.asStateFlow()
    
    private var engineStateJob: kotlinx.coroutines.Job? = null

    init {
        observeEngine()
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
        screenCaptureProvider?.stop()
        super.onCleared()
    }
    
    private fun observeEngine() {
        engineStateJob?.cancel()
        engineStateJob = viewModelScope.launch {
            executionEngine.state.collect { _executionState.value = it }
        }
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
        pushUndo()
        val node: FlowNode = when (type) {
            FlowNodeType.START -> StartNode(position = position)
            FlowNodeType.GESTURE -> GestureNode(position = position)
            FlowNodeType.VISUAL_TRIGGER -> VisualTriggerNode(position = position)
            FlowNodeType.SCREEN_ML -> ScreenMLNode(position = position)
            FlowNodeType.DELAY -> DelayNode(position = position)
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
                showEdgeConfig = false
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
        _state.update { it.copy(isConnecting = true, connectFromNodeId = fromNodeId) }
    }

    fun completeConnection(toNodeId: String) {
        val fromId = _state.value.connectFromNodeId ?: return
        if (fromId == toNodeId) {
            cancelConnection()
            return
        }

        // Prevent duplicate edges
        val existingEdge = _state.value.graph.edges.find {
            it.fromNodeId == fromId && it.toNodeId == toNodeId
        }
        if (existingEdge != null) {
            cancelConnection()
            return
        }

        pushUndo()
        val edge = FlowEdge(
            fromNodeId = fromId,
            toNodeId = toNodeId
        )
        _state.update { state ->
            state.copy(
                graph = state.graph.withEdge(edge),
                isConnecting = false,
                connectFromNodeId = null,
                isDirty = true
            )
        }
    }

    fun cancelConnection() {
        _state.update { it.copy(isConnecting = false, connectFromNodeId = null) }
    }

    fun selectEdge(edgeId: String?) {
        _state.update {
            it.copy(
                selectedEdgeId = edgeId,
                selectedNodeId = null,
                showEdgeConfig = edgeId != null,
                showNodeConfig = false
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
        _state.update { it.copy(showNodePalette = !it.showNodePalette) }
    }

    fun dismissNodeConfig() {
        _state.update { it.copy(showNodeConfig = false) }
    }

    fun dismissEdgeConfig() {
        _state.update { it.copy(showEdgeConfig = false) }
    }

    // ─── Execution ───────────────────────────────────────────────────────

    fun executeFlow(resultCode: Int? = null, resultData: android.content.Intent? = null) {
        saveFlow()
        executionEngine.stop()
        screenCaptureProvider?.stop()
        
        if (resultCode != null && resultData != null && resultCode == android.app.Activity.RESULT_OK) {
            screenCaptureProvider = com.autonion.automationcompanion.features.flow_automation.engine.ScreenCaptureProvider(getApplication()).apply {
                start(resultCode, resultData)
            }
        } else {
            screenCaptureProvider = null
        }
        
        executionEngine = FlowExecutionEngine(getApplication(), screenCaptureProvider)
        observeEngine()
        
        executionEngine.execute(_state.value.graph, viewModelScope)
    }

    fun stopExecution() {
        executionEngine.stop()
        screenCaptureProvider?.stop()
        screenCaptureProvider = null
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
                    (node as? ScreenMLNode)?.copy(automationStepsJson = json, captureImagePath = imgPath) ?: node
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
        val intent = android.content.Intent()
        
        when (node) {
            is GestureNode -> {
                intent.setClass(app, com.autonion.automationcompanion.features.gesture_recording_playback.overlay.OverlayService::class.java)
                intent.action = "com.autonion.ACTION_START_OVERLAY"
                intent.putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_MODE, true)
                intent.putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_NODE_ID, node.id)
                app.startService(intent)
            }
            is VisualTriggerNode -> {
                val intent = android.content.Intent(app, com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity::class.java).apply {
                    action = com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.ACTION_START_VISUAL_OVERLAY
                    putExtra(com.autonion.automationcompanion.features.flow_automation.ui.FlowMediaProjectionActivity.EXTRA_NODE_ID, node.id)
                }
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                app.startActivity(intent)
            }
            is ScreenMLNode -> {
                val intent = android.content.Intent()
                intent.setClass(app, com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService::class.java)
                intent.action = "START_CAPTURE_PHASE"
                intent.putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_MODE, true)
                intent.putExtra(com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract.EXTRA_FLOW_NODE_ID, node.id)
                app.startService(intent)
            }
            else -> return
        }
        
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        app.startActivity(homeIntent)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun updateNodePosField(node: FlowNode, pos: NodePosition): FlowNode {
        return when (node) {
            is StartNode -> node.copy(position = pos)
            is GestureNode -> node.copy(position = pos)
            is VisualTriggerNode -> node.copy(position = pos)
            is ScreenMLNode -> node.copy(position = pos)
            is DelayNode -> node.copy(position = pos)
        }
    }
}
