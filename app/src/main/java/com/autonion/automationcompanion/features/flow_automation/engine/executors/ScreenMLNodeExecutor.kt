package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.ScreenMLMode
import com.autonion.automationcompanion.features.flow_automation.model.ScreenMLNode

private const val TAG = "ScreenMLNodeExecutor"

/**
 * Executor for [ScreenMLNode].
 * - OCR mode: captures screen and extracts text, writes to FlowContext.
 * - Object Detection mode: finds UI elements and writes coordinates.
 *
 * For Pass 1, this is a placeholder. Full TFLite integration comes in Pass 2.
 */
class ScreenMLNodeExecutor : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val mlNode = node as? ScreenMLNode
            ?: return NodeResult.Failure("Expected ScreenMLNode but got ${node::class.simpleName}")

        Log.d(TAG, "Screen ML: mode=${mlNode.mode}, outputKey=${mlNode.outputContextKey}")

        return when (mlNode.mode) {
            ScreenMLMode.OCR -> executeOCR(mlNode, context)
            ScreenMLMode.OBJECT_DETECTION -> executeObjectDetection(mlNode, context)
        }
    }

    private suspend fun executeOCR(node: ScreenMLNode, context: FlowContext): NodeResult {
        // TODO (Pass 2): Integrate with MediaProjection screen capture + TFLite OCR model
        // For now, write a placeholder result
        Log.d(TAG, "OCR placeholder: would capture screen and extract text")

        context.put(node.outputContextKey, "")  // Empty text result
        context.put("${node.outputContextKey}_success", true)

        return NodeResult.Success
    }

    private suspend fun executeObjectDetection(node: ScreenMLNode, context: FlowContext): NodeResult {
        // TODO (Pass 2): Integrate with YOLO-based UI element detection
        Log.d(TAG, "Object Detection placeholder: would detect UI elements")

        context.put(node.outputContextKey, "[]")  // Empty detections
        context.put("${node.outputContextKey}_success", true)

        if (node.targetLabel != null) {
            context.put("${node.outputContextKey}_target_found", false)
        }

        return NodeResult.Success
    }
}
