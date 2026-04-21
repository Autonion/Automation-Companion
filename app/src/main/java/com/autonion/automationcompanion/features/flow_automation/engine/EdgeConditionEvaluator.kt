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
     *
     * Note: WaitSeconds conditions return true immediately — the actual delay
     * is applied by [applyEdgeDelay] after edge selection to avoid blocking
     * evaluation of other edges.
     */
    suspend fun evaluate(edge: FlowEdge, context: FlowContext): Boolean {
        val condition = edge.condition ?: return true // No condition = always follow

        return when (condition) {
            is EdgeCondition.Always -> true

            is EdgeCondition.WaitSeconds -> {
                // Bug #3 fix: Don't delay during evaluation — just mark as eligible.
                // The actual delay is applied after edge selection via applyEdgeDelay().
                Log.d(TAG, "Edge ${edge.id}: WaitSeconds(${condition.seconds}s) — eligible")
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
                // Bug #5 fix: StopExecution is handled separately in resolveNextEdge
                // to prevent it from preempting conditional edges.
                true
            }

            is EdgeCondition.Retry -> {
                // Bug #2 fix: Retry edges are excluded from resolveNextEdge entirely.
                // They are only consumed by the engine on failure.
                // Returning false here as a safety measure.
                false
            }
        }
    }

    /**
     * From a list of outgoing edges, pick the first one whose condition evaluates to true.
     * Non-failure edges are evaluated first; failure edges are separate.
     * Retry edges are excluded (handled by the engine on failure).
     * StopExecution edges are evaluated after normal + else edges to prevent preemption.
     * 'Else' edges are evaluated after normal edges.
     */
    suspend fun resolveNextEdge(
        edges: List<FlowEdge>,
        context: FlowContext
    ): FlowEdge? {
        // Bug #2 fix: Filter out Retry edges — they're only handled by the engine on failure
        // Bug #5 fix: Filter out StopExecution edges — evaluate them last to prevent preemption
        val normalEdges = edges.filter {
            !it.isFailurePath &&
            it.condition !is EdgeCondition.Else &&
            it.condition !is EdgeCondition.Retry &&
            it.condition !is EdgeCondition.StopExecution
        }
        val elseEdges = edges.filter { !it.isFailurePath && it.condition is EdgeCondition.Else }
        val stopEdges = edges.filter { !it.isFailurePath && it.condition is EdgeCondition.StopExecution }

        // Evaluate all normal positive/negative conditions first
        for (edge in normalEdges) {
            if (evaluate(edge, context)) return edge
        }

        // Fallback to Else edges if standard logic doesn't match
        for (edge in elseEdges) {
            if (evaluate(edge, context)) return edge
        }

        // StopExecution only triggers if no other edge matched
        for (edge in stopEdges) {
            if (evaluate(edge, context)) return edge
        }

        return null
    }

    /**
     * Apply the delay for a WaitSeconds edge after it has been selected.
     * Call this after [resolveNextEdge] returns the chosen edge.
     */
    suspend fun applyEdgeDelay(edge: FlowEdge) {
        val condition = edge.condition
        if (condition is EdgeCondition.WaitSeconds) {
            Log.d(TAG, "Applying WaitSeconds delay: ${condition.seconds}s")
            delay((condition.seconds * 1000).toLong())
        }
    }
}
