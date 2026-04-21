package com.autonion.automationcompanion.features.flow_automation.engine

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.flow_automation.engine.executors.*
import com.autonion.automationcompanion.features.flow_automation.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "FlowExecutionEngine"
private val DBG_CATEGORY = LogCategory.FLOW_BUILDER

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
    @Volatile
    private var isPaused = false

    // Executor registry — wire real implementations when ScreenCaptureProvider is available
    private val executors = mapOf<FlowNodeType, NodeExecutor>(
        FlowNodeType.START to StartNodeExecutor(appContext),
        FlowNodeType.GESTURE to GestureNodeExecutor(),
        FlowNodeType.VISUAL_TRIGGER to VisualTriggerNodeExecutor(screenCaptureProvider),
        FlowNodeType.SCREEN_ML to ScreenMLNodeExecutor(appContext, screenCaptureProvider),
        FlowNodeType.DELAY to DelayNodeExecutor(),
        FlowNodeType.LAUNCH_APP to LaunchAppNodeExecutor(appContext),
        FlowNodeType.REPEAT to RepeatNodeExecutor()
    )

    /**
     * Execute the given flow graph in a coroutine scope.
     */
    fun execute(graph: FlowGraph, scope: CoroutineScope) {
        if (executionJob?.isActive == true) {
            Log.w(TAG, "Flow already executing — stop first before starting a new one")
            DebugLogger.warning(appContext, DBG_CATEGORY, "Flow Already Running", "Attempted to start a new flow while one is already executing", TAG)
            return
        }

        flowContext.clear()
        isPaused = false

        executionJob = scope.launch(Dispatchers.Default) {
            try {
                executeGraph(graph)
            } catch (e: CancellationException) {
                Log.d(TAG, "Flow execution cancelled")
                DebugLogger.info(appContext, DBG_CATEGORY, "Flow Cancelled", "Flow execution was cancelled by user", TAG)
                _state.value = FlowExecutionState.Stopped
            } catch (e: Exception) {
                Log.e(TAG, "Flow execution error: ${e.message}", e)
                DebugLogger.error(appContext, DBG_CATEGORY, "Flow Error", "Execution error: ${e.message}", TAG)
                _state.value = FlowExecutionState.Error(null, e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun executeGraph(graph: FlowGraph) {
        val startNode = graph.findStartNode()
        if (startNode == null) {
            _state.value = FlowExecutionState.Error(null, "No Start node found in flow")
            DebugLogger.error(appContext, DBG_CATEGORY, "No Start Node", "Flow has no Start node — cannot execute", TAG)
            return
        }

        var currentNode: FlowNode? = startNode
        Log.d(TAG, "Starting flow '${graph.name}' at node ${startNode.id}")
        DebugLogger.info(appContext, DBG_CATEGORY, "Flow Started", "Starting flow '${graph.name}' with ${graph.nodes.size} nodes", TAG)

        while (currentNode != null) {
            // Check for pause
            while (isPaused) {
                delay(200)
            }

            currentCoroutineContext().ensureActive()

            val node = currentNode
            Log.d(TAG, "Executing node: ${node.label} (${node.nodeType})")
            _state.value = FlowExecutionState.Running(node.id, node.label)

            // Find the right executor
            val executor = executors[node.nodeType]
            if (executor == null) {
                _state.value = FlowExecutionState.Error(node.id, "No executor for type ${node.nodeType}")
                DebugLogger.error(appContext, DBG_CATEGORY, "Missing Executor", "No executor registered for node type ${node.nodeType}", TAG)
                return
            }

            // Execute with timeout
            val result = try {
                withTimeout(node.timeoutMs) {
                    executor.execute(node, flowContext)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Node ${node.id} timed out after ${node.timeoutMs}ms")
                DebugLogger.warning(appContext, DBG_CATEGORY, "Node Timeout", "Node '${node.label}' timed out after ${node.timeoutMs}ms", TAG)
                NodeResult.Failure("Timed out after ${node.timeoutMs}ms")
            }

            when (result) {
                is NodeResult.Success -> {
                    Log.d(TAG, "Node ${node.label} succeeded")
                    DebugLogger.success(appContext, DBG_CATEGORY, "Node Succeeded", "'${node.label}' (${node.nodeType}) completed successfully", TAG)
                    _state.value = FlowExecutionState.NodeCompleted(node.id)

                    // Find next node via edge evaluation
                    val outgoingEdges = graph.outgoingEdges(node.id)
                    if (outgoingEdges.isEmpty()) {
                        Log.d(TAG, "No outgoing edges — flow complete")
                        currentNode = null
                    } else if (node is RepeatNode) {
                        // ── Repeat/loop handling ──
                        val repeatCount = node.repeatCount
                        val isInfinite = repeatCount == 0
                        val nextEdge = EdgeConditionEvaluator.resolveNextEdge(outgoingEdges, flowContext)

                        if (nextEdge != null) {
                            var iteration = 0
                            while (isInfinite || iteration < repeatCount) {
                                currentCoroutineContext().ensureActive()
                                while (isPaused) { delay(200) }

                                iteration++
                                Log.d(TAG, "Repeat '${node.label}' iteration $iteration" +
                                        if (!isInfinite) "/$repeatCount" else " (∞)")
                                DebugLogger.info(appContext, DBG_CATEGORY, "Repeat Iteration",
                                    "'${node.label}' iteration $iteration" +
                                            if (!isInfinite) "/$repeatCount" else " (infinite)", TAG)

                                // Execute the sub-graph starting from the repeat's first child
                                var subNode: FlowNode? = graph.nodeById(nextEdge.toNodeId)
                                while (subNode != null) {
                                    currentCoroutineContext().ensureActive()
                                    while (isPaused) { delay(200) }

                                    // Don't re-enter another RepeatNode's loop from inside
                                    _state.value = FlowExecutionState.Running(subNode.id, subNode.label)
                                    val subExecutor = executors[subNode.nodeType]
                                    if (subExecutor == null) {
                                        _state.value = FlowExecutionState.Error(subNode.id, "No executor for ${subNode.nodeType}")
                                        return
                                    }

                                    val subResult = try {
                                        withTimeout(subNode.timeoutMs) {
                                            subExecutor.execute(subNode, flowContext)
                                        }
                                    } catch (e: TimeoutCancellationException) {
                                        NodeResult.Failure("Timed out after ${subNode.timeoutMs}ms")
                                    }

                                    when (subResult) {
                                        is NodeResult.Success -> {
                                            _state.value = FlowExecutionState.NodeCompleted(subNode.id)
                                            val subEdges = graph.outgoingEdges(subNode.id)
                                            if (subEdges.isEmpty()) {
                                                subNode = null // end of sub-chain
                                            } else {
                                                val subNext = EdgeConditionEvaluator.resolveNextEdge(subEdges, flowContext)
                                                if (subNext != null) {
                                                    EdgeConditionEvaluator.applyEdgeDelay(subNext)
                                                    subNode = graph.nodeById(subNext.toNodeId)
                                                } else {
                                                    subNode = null
                                                }
                                            }
                                        }
                                        is NodeResult.Failure -> {
                                            Log.e(TAG, "Sub-node ${subNode.label} failed in repeat: ${subResult.reason}")
                                            DebugLogger.error(appContext, DBG_CATEGORY, "Repeat Sub-Node Failed",
                                                "'${subNode.label}' failed during repeat: ${subResult.reason}", TAG)
                                            _state.value = FlowExecutionState.Error(subNode.id, subResult.reason)
                                            return
                                        }
                                    }
                                }

                                // Delay between iterations
                                if (node.delayBetweenMs > 0 && (isInfinite || iteration < repeatCount)) {
                                    delay(node.delayBetweenMs)
                                }
                            }

                            Log.d(TAG, "Repeat '${node.label}' completed all iterations")
                            DebugLogger.success(appContext, DBG_CATEGORY, "Repeat Completed",
                                "'${node.label}' completed $repeatCount iterations", TAG)
                        }
                        // After repeat finishes, flow continues to whatever is AFTER the last sub-node
                        // (or ends if there are no more nodes)
                        currentNode = null
                    } else {
                        val nextEdge = EdgeConditionEvaluator.resolveNextEdge(outgoingEdges, flowContext)
                        if (nextEdge?.condition is EdgeCondition.StopExecution) {
                            Log.d(TAG, "StopExecution condition encountered — halting flow")
                            currentNode = null
                        } else if (nextEdge != null) {
                            // Bug #3 fix: Apply WaitSeconds delay AFTER edge selection
                            EdgeConditionEvaluator.applyEdgeDelay(nextEdge)
                            currentNode = graph.nodeById(nextEdge.toNodeId)
                        } else {
                            Log.d(TAG, "No matching edge condition — flow complete")
                            currentNode = null
                        }
                    }
                }

                is NodeResult.Failure -> {
                    Log.e(TAG, "Node ${node.label} failed: ${result.reason}")
                    DebugLogger.error(appContext, DBG_CATEGORY, "Node Failed", "'${node.label}' failed: ${result.reason}", TAG)

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
                            DebugLogger.info(appContext, DBG_CATEGORY, "Node Retry", "Retrying '${node.label}' (${currentRetries + 1}/${retry.maxAttempts})", TAG)
                            delay(retry.delayMs)
                            // Don't advance currentNode — retry same node
                            continue
                        } else {
                            Log.e(TAG, "Max retries exhausted for ${node.label}")
                            DebugLogger.error(appContext, DBG_CATEGORY, "Retries Exhausted", "Max retries (${retry.maxAttempts}) exhausted for '${node.label}'", TAG)
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
        DebugLogger.success(appContext, DBG_CATEGORY, "Flow Completed", "Flow '${graph.name}' finished successfully", TAG)
    }

    fun pause() {
        isPaused = true
        Log.d(TAG, "Flow paused")
        DebugLogger.info(appContext, DBG_CATEGORY, "Flow Paused", "Flow execution paused by user", TAG)
    }

    fun resume() {
        isPaused = false
        Log.d(TAG, "Flow resumed")
        DebugLogger.info(appContext, DBG_CATEGORY, "Flow Resumed", "Flow execution resumed", TAG)
    }

    fun stop() {
        executionJob?.cancel()
        executionJob = null
        flowContext.clear()
        isPaused = false
        _state.value = FlowExecutionState.Stopped
        Log.d(TAG, "Flow stopped")
        DebugLogger.info(appContext, DBG_CATEGORY, "Flow Stopped", "Flow execution stopped", TAG)
    }

    fun isRunning(): Boolean = executionJob?.isActive == true
}
