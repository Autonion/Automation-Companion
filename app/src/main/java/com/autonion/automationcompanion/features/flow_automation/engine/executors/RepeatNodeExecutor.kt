package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.RepeatNode

private const val TAG = "RepeatNodeExecutor"

/**
 * Executor for [RepeatNode].
 *
 * The actual looping logic lives in [FlowExecutionEngine.executeGraph] because
 * it needs access to the full graph for sub-node traversal. This executor acts
 * as a pass-through gate — it validates the node configuration and immediately
 * returns [NodeResult.Success] so the engine's repeat-handling block takes over.
 */
class RepeatNodeExecutor : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val repeatNode = node as? RepeatNode
            ?: return NodeResult.Failure("Expected RepeatNode but got ${node::class.simpleName}")

        val label = if (repeatNode.repeatCount == 0) "infinite" else "${repeatNode.repeatCount}×"
        Log.d(TAG, "Repeat node '${repeatNode.label}' configured for $label iterations")

        return NodeResult.Success
    }
}
