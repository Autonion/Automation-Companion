package com.autonion.automationcompanion.features.flow_automation.engine

import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode

/**
 * Result of executing a single node.
 */
sealed class NodeResult {
    /** Node completed successfully. */
    object Success : NodeResult()

    /** Node failed with a reason. */
    data class Failure(val reason: String) : NodeResult()
}

/**
 * Interface for node type-specific execution logic.
 * Each concrete [FlowNode] type has a matching executor.
 */
interface NodeExecutor {
    /**
     * Execute the given [node], reading/writing to [context] as needed.
     *
     * @param node    The node to execute.
     * @param context The shared blackboard for inter-node data passing.
     * @return [NodeResult.Success] or [NodeResult.Failure].
     */
    suspend fun execute(node: FlowNode, context: FlowContext): NodeResult
}
