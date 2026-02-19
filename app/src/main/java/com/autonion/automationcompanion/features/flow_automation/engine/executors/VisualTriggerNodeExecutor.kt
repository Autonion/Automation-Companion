package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.VisualTriggerNode

private const val TAG = "VisualTriggerExecutor"

/**
 * Executor for [VisualTriggerNode].
 * Uses OpenCV template matching to find a reference image on screen.
 *
 * For Pass 1, this is a placeholder that writes a simulated result to FlowContext.
 * Full integration with VisionMediaProjection + native matching comes in Pass 2.
 */
class VisualTriggerNodeExecutor : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val vtNode = node as? VisualTriggerNode
            ?: return NodeResult.Failure("Expected VisualTriggerNode but got ${node::class.simpleName}")

        if (vtNode.templateImagePath.isBlank()) {
            return NodeResult.Failure("No template image configured")
        }

        Log.d(TAG, "Visual trigger: searching for template at ${vtNode.templateImagePath}")
        Log.d(TAG, "Threshold: ${vtNode.threshold}, Region: (${vtNode.searchRegionX}, ${vtNode.searchRegionY}, ${vtNode.searchRegionWidth}x${vtNode.searchRegionHeight})")

        // TODO (Pass 2): Integrate with VisionMediaProjection + native OpenCV
        // For now, write a placeholder result to FlowContext
        // In full implementation, this would:
        // 1. Capture screen via MediaProjection
        // 2. Run native template matching
        // 3. Return match coordinates if score > threshold

        // Mark as found=false since we can't actually match yet without MediaProjection integration
        context.put(vtNode.outputContextKey, "not_found")
        context.put("${vtNode.outputContextKey}_found", false)

        Log.d(TAG, "Visual trigger placeholder executed — wrote '${vtNode.outputContextKey}' to FlowContext")
        return NodeResult.Success
    }
}
