package com.autonion.automationcompanion.features.flow_automation.engine

import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.model.EdgeCondition
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowEdge
import kotlinx.coroutines.delay

private const val TAG = "EdgeConditionEvaluator"

/**
 * Evaluates edge conditions to determine which outgoing edge to traverse next.
 */
object EdgeConditionEvaluator {

    /**
     * Evaluate a single edge condition against the current FlowContext.
     * Returns true if the edge should be followed, false to skip.
     * If the condition involves a delay, this function suspends for that duration.
     */
    suspend fun evaluate(edge: FlowEdge, context: FlowContext): Boolean {
        val condition = edge.condition ?: return true // No condition = always follow

        return when (condition) {
            is EdgeCondition.Always -> true

            is EdgeCondition.WaitSeconds -> {
                Log.d(TAG, "Edge ${edge.id}: waiting ${condition.seconds}s")
                delay((condition.seconds * 1000).toLong())
                true
            }

            is EdgeCondition.IfTextContains -> {
                val text = context.getString(condition.contextKey) ?: ""
                val result = text.contains(condition.substring, ignoreCase = true)
                Log.d(TAG, "Edge ${edge.id}: ifTextContains('${condition.substring}' in '$text') = $result")
                result
            }

            is EdgeCondition.IfContextEquals -> {
                val value = context.getString(condition.key) ?: ""
                val result = value == condition.value
                Log.d(TAG, "Edge ${edge.id}: ifContextEquals('${condition.key}'='$value' == '${condition.value}') = $result")
                result
            }

            is EdgeCondition.IfImageFound -> {
                val found = context.getBoolean("${condition.contextKey}_found") ?: false
                Log.d(TAG, "Edge ${edge.id}: ifImageFound('${condition.contextKey}') = $found")
                found
            }

            is EdgeCondition.IfNotTextContains -> {
                val text = context.getString(condition.contextKey) ?: ""
                val result = !text.contains(condition.substring, ignoreCase = true)
                Log.d(TAG, "Edge ${edge.id}: ifNotTextContains('${condition.substring}' in '$text') = $result")
                result
            }

            is EdgeCondition.IfNotContextEquals -> {
                val value = context.getString(condition.key) ?: ""
                val result = value != condition.value
                Log.d(TAG, "Edge ${edge.id}: ifNotContextEquals('${condition.key}'='$value' != '${condition.value}') = $result")
                result
            }

            is EdgeCondition.IfNotImageFound -> {
                val found = context.getBoolean("${condition.contextKey}_found") ?: false
                Log.d(TAG, "Edge ${edge.id}: ifNotImageFound('${condition.contextKey}') = ${!found}")
                !found
            }

            is EdgeCondition.Else -> {
                // Else evaluates to true only during the fallback pass in resolveNextEdge
                true
            }

            is EdgeCondition.StopExecution -> {
                // Treated as a valid path to follow, engine halts when encountered
                true
            }

            is EdgeCondition.Retry -> {
                // Retry edges are handled by the execution engine, not here.
                // The evaluator gives them a pass; the engine handles retry logic.
                true
            }
        }
    }

    /**
     * From a list of outgoing edges, pick the first one whose condition evaluates to true.
     * Non-failure edges are evaluated first; failure edges are separate.
     * 'Else' edges are evaluated last.
     */
    suspend fun resolveNextEdge(
        edges: List<FlowEdge>,
        context: FlowContext
    ): FlowEdge? {
        val normalEdges = edges.filter { !it.isFailurePath && it.condition !is EdgeCondition.Else }
        val elseEdges = edges.filter { !it.isFailurePath && it.condition is EdgeCondition.Else }

        // Evaluate all normal positive/negative conditions
        for (edge in normalEdges) {
            if (evaluate(edge, context)) return edge
        }

        // Fallback to Else edges if standard logic doesn't match
        for (edge in elseEdges) {
            if (evaluate(edge, context)) return edge
        }

        return null
    }
}
