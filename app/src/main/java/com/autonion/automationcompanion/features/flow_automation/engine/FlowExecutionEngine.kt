package com.autonion.automationcompanion.features.flow_automation.engine

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.executors.*
import com.autonion.automationcompanion.features.flow_automation.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "FlowExecutionEngine"

/**
 * State emitted during flow execution for UI updates.
 */
sealed class FlowExecutionState {
    object Idle : FlowExecutionState()
    data class Running(val currentNodeId: String, val currentNodeLabel: String) : FlowExecutionState()
    data class NodeCompleted(val nodeId: String) : FlowExecutionState()
    data class Error(val nodeId: String?, val message: String) : FlowExecutionState()
    object Completed : FlowExecutionState()
    object Stopped : FlowExecutionState()
}

/**
 * Core graph-traversal execution engine.
 *
 * Walks the directed graph from the StartNode, executing each node via its
 * [NodeExecutor] and following edges based on [EdgeConditionEvaluator].
 */
class FlowExecutionEngine(
    private val appContext: Context,
    private val screenCaptureProvider: ScreenCaptureProvider? = null
) {

    private val _state = MutableStateFlow<FlowExecutionState>(FlowExecutionState.Idle)
    val state: StateFlow<FlowExecutionState> = _state.asStateFlow()

    private val flowContext = FlowContext()
    private var executionJob: Job? = null
    private var isPaused = false

    // Executor registry — wire real implementations when ScreenCaptureProvider is available
    private val executors = mapOf<FlowNodeType, NodeExecutor>(
        FlowNodeType.START to StartNodeExecutor(appContext),
        FlowNodeType.GESTURE to GestureNodeExecutor(),
        FlowNodeType.VISUAL_TRIGGER to VisualTriggerNodeExecutor(screenCaptureProvider),
        FlowNodeType.SCREEN_ML to ScreenMLNodeExecutor(appContext, screenCaptureProvider),
        FlowNodeType.DELAY to DelayNodeExecutor()
    )

    /**
     * Execute the given flow graph in a coroutine scope.
     */
    fun execute(graph: FlowGraph, scope: CoroutineScope) {
        if (executionJob?.isActive == true) {
            Log.w(TAG, "Flow already executing — stop first before starting a new one")
            return
        }

        flowContext.clear()
        isPaused = false

        executionJob = scope.launch(Dispatchers.Default) {
            try {
                executeGraph(graph)
            } catch (e: CancellationException) {
                Log.d(TAG, "Flow execution cancelled")
                _state.value = FlowExecutionState.Stopped
            } catch (e: Exception) {
                Log.e(TAG, "Flow execution error: ${e.message}", e)
                _state.value = FlowExecutionState.Error(null, e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun executeGraph(graph: FlowGraph) {
        val startNode = graph.findStartNode()
        if (startNode == null) {
            _state.value = FlowExecutionState.Error(null, "No Start node found in flow")
            return
        }

        var currentNode: FlowNode? = startNode
        Log.d(TAG, "Starting flow '${graph.name}' at node ${startNode.id}")

        while (currentNode != null) {
            // Check for pause
            while (isPaused) {
                delay(200)
            }

            currentCoroutineContext().ensureActive()

            val node = currentNode
            Log.d(TAG, "Executing node: ${node.label} (${node.nodeType})")

            // Find the right executor
            val executor = executors[node.nodeType]
            if (executor == null) {
                _state.value = FlowExecutionState.Error(node.id, "No executor for type ${node.nodeType}")
                return
            }

            // Execute with timeout
            val result = try {
                withTimeout(node.timeoutMs) {
                    executor.execute(node, flowContext)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Node ${node.id} timed out after ${node.timeoutMs}ms")
                NodeResult.Failure("Timed out after ${node.timeoutMs}ms")
            }

            when (result) {
                is NodeResult.Success -> {
                    Log.d(TAG, "Node ${node.label} succeeded")
                    _state.value = FlowExecutionState.NodeCompleted(node.id)

                    // Find next node via edge evaluation
                    val outgoingEdges = graph.outgoingEdges(node.id)
                    if (outgoingEdges.isEmpty()) {
                        Log.d(TAG, "No outgoing edges — flow complete")
                        currentNode = null
                    } else {
                        val nextEdge = EdgeConditionEvaluator.resolveNextEdge(outgoingEdges, flowContext)
                        currentNode = nextEdge?.let { graph.nodeById(it.toNodeId) }
                        if (currentNode == null && nextEdge == null) {
                            Log.d(TAG, "No matching edge condition — flow complete")
                        }
                    }
                }

                is NodeResult.Failure -> {
                    Log.e(TAG, "Node ${node.label} failed: ${result.reason}")

                    // Check for retry edges
                    val retryEdge = graph.outgoingEdges(node.id).find {
                        it.condition is EdgeCondition.Retry
                    }

                    if (retryEdge != null) {
                        val retry = retryEdge.condition as EdgeCondition.Retry
                        val retryKey = "retry_count_${node.id}"
                        val currentRetries = flowContext.getInt(retryKey) ?: 0

                        if (currentRetries < retry.maxAttempts) {
                            flowContext.put(retryKey, currentRetries + 1)
                            Log.d(TAG, "Retrying node ${node.label} (${currentRetries + 1}/${retry.maxAttempts})")
                            delay(retry.delayMs)
                            // Don't advance currentNode — retry same node
                            continue
                        } else {
                            Log.e(TAG, "Max retries exhausted for ${node.label}")
                        }
                    }

                    // Check for failure edge
                    val failureEdge = graph.failureEdge(node.id)
                    if (failureEdge != null) {
                        Log.d(TAG, "Following failure edge to ${failureEdge.toNodeId}")
                        currentNode = graph.nodeById(failureEdge.toNodeId)
                    } else {
                        _state.value = FlowExecutionState.Error(node.id, result.reason)
                        return
                    }
                }
            }
        }

        _state.value = FlowExecutionState.Completed
        Log.d(TAG, "Flow execution completed successfully")
    }

    fun pause() {
        isPaused = true
        Log.d(TAG, "Flow paused")
    }

    fun resume() {
        isPaused = false
        Log.d(TAG, "Flow resumed")
    }

    fun stop() {
        executionJob?.cancel()
        executionJob = null
        flowContext.clear()
        isPaused = false
        _state.value = FlowExecutionState.Stopped
        Log.d(TAG, "Flow stopped")
    }

    fun isRunning(): Boolean = executionJob?.isActive == true
}
