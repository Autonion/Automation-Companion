package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.DelayNode
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import kotlinx.coroutines.delay

private const val TAG = "DelayNodeExecutor"

/**
 * Executor for [DelayNode]. Simply suspends for the configured duration.
 */
class DelayNodeExecutor : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val delayNode = node as? DelayNode
            ?: return NodeResult.Failure("Expected DelayNode but got ${node::class.simpleName}")

        Log.d(TAG, "Delaying for ${delayNode.delayMs}ms")
        delay(delayNode.delayMs)
        Log.d(TAG, "Delay complete")

        return NodeResult.Success
    }
}
