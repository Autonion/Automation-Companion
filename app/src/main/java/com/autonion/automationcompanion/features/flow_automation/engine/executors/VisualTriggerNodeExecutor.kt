package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.graphics.BitmapFactory
import android.util.Log
import com.autonion.automationcompanion.core.vision.VisionNativeBridge
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.engine.ScreenCaptureProvider
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.VisualTriggerNode

private const val TAG = "VisualTriggerExecutor"

/**
 * Executor for [VisualTriggerNode].
 *
 * Captures the current screen via [ScreenCaptureProvider], loads the template
 * image, and runs native OpenCV template matching through [VisionNativeBridge].
 * Writes match coordinates to [FlowContext] on success.
 */
class VisualTriggerNodeExecutor(
    private val screenCaptureProvider: ScreenCaptureProvider? = null
) : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val vtNode = node as? VisualTriggerNode
            ?: return NodeResult.Failure("Expected VisualTriggerNode but got ${node::class.simpleName}")

        if (vtNode.templateImagePath.isBlank()) {
            return NodeResult.Failure("No template image configured")
        }

        val provider = screenCaptureProvider
            ?: return NodeResult.Failure("Screen capture not available — MediaProjection not started")

        Log.d(TAG, "Visual trigger: template=${vtNode.templateImagePath}, threshold=${vtNode.threshold}")

        // 1. Decode the template image from disk
        val templateBitmap = BitmapFactory.decodeFile(vtNode.templateImagePath)
            ?: return NodeResult.Failure("Failed to decode template image: ${vtNode.templateImagePath}")

        // 2. Capture the current screen
        val screenBitmap = provider.captureFrame()
            ?: return NodeResult.Failure("Failed to capture screen frame")

        try {
            // 3. Load template into the native bridge with a unique ID
            // Use hashCode as integer ID for the native bridge
            val templateId = vtNode.id.hashCode()
            VisionNativeBridge.nativeClearTemplates()
            VisionNativeBridge.addTemplate(templateId, templateBitmap)

            // 4. Run native template matching
            val results = VisionNativeBridge.match(screenBitmap)

            // 5. Find our template result
            val match = results.firstOrNull { it.id == templateId }

            if (match != null && match.matched && match.score >= vtNode.threshold) {
                Log.d(TAG, "  ✓ Match found: score=${match.score}, at=(${match.x},${match.y}), size=${match.width}x${match.height}")

                // Write match coordinates to FlowContext
                val cx = match.x + match.width / 2
                val cy = match.y + match.height / 2
                context.put("${vtNode.outputContextKey}_found", true)
                context.put("${vtNode.outputContextKey}_x", cx)
                context.put("${vtNode.outputContextKey}_y", cy)
                context.put("${vtNode.outputContextKey}_width", match.width)
                context.put("${vtNode.outputContextKey}_height", match.height)
                context.put("${vtNode.outputContextKey}_score", match.score)
                context.put(vtNode.outputContextKey, "${cx},${cy}")

                return NodeResult.Success
            } else {
                val score = match?.score ?: 0f
                Log.d(TAG, "  ✗ No match above threshold (score=$score, need≥${vtNode.threshold})")
                context.put("${vtNode.outputContextKey}_found", false)
                context.put(vtNode.outputContextKey, "not_found")
                return NodeResult.Failure("Template not found on screen (best score: $score)")
            }
        } finally {
            templateBitmap.recycle()
            // Don't recycle screenBitmap — it's managed by the VisionMediaProjection flow
            VisionNativeBridge.nativeClearTemplates()
        }
    }
}
