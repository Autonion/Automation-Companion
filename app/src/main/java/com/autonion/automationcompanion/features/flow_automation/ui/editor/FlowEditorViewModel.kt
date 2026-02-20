package com.autonion.automationcompanion.features.flow_automation.ui.editor

import android.app.Application
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.autonion.automationcompanion.features.flow_automation.data.FlowRepository
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionEngine
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionState
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
    private val executionEngine = FlowExecutionEngine(application)

    private val _state = MutableStateFlow(FlowEditorState())
    val state: StateFlow<FlowEditorState> = _state.asStateFlow()

    val executionState: StateFlow<FlowExecutionState> = executionEngine.state

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

    fun executeFlow() {
        saveFlow()
        executionEngine.execute(_state.value.graph, viewModelScope)
    }

    fun stopExecution() {
        executionEngine.stop()
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
