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
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode
import com.autonion.automationcompanion.features.visual_trigger.service.VisionActionExecutor

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

        val provider = screenCaptureProvider
            ?: return NodeResult.Failure("Screen capture not available — MediaProjection not started")

        if (vtNode.visionPresetJson.isNotEmpty()) {
            return executePreset(vtNode, provider, context)
        }

        if (vtNode.templateImagePath.isBlank()) {
            return NodeResult.Failure("No template image configured")
        }

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

    private suspend fun executePreset(node: VisualTriggerNode, provider: ScreenCaptureProvider, context: FlowContext): NodeResult {
        try {
            val preset = kotlinx.serialization.json.Json.decodeFromString<VisionPreset>(node.visionPresetJson)
            Log.d(TAG, "Playing back VisionPreset: ${preset.name} with ${preset.regions.size} regions, mode: ${preset.executionMode}")
            
            for (region in preset.regions) {
                val templateBitmap = BitmapFactory.decodeFile(region.templatePath)
                if (templateBitmap == null) {
                    Log.e(TAG, "Failed to decode region template: ${region.templatePath}")
                    if (preset.executionMode == ExecutionMode.MANDATORY_SEQUENTIAL) {
                        return NodeResult.Failure("Failed to decode region template")
                    }
                    continue
                }
                
                // Allow UI to update
                kotlinx.coroutines.delay(200)
                
                val screenBitmap = provider.captureFrame()
                if (screenBitmap == null) {
                    templateBitmap.recycle()
                    return NodeResult.Failure("Failed to capture screen frame")
                }
                
                try {
                    VisionNativeBridge.nativeClearTemplates()
                    VisionNativeBridge.addTemplate(region.id, templateBitmap)
                    val results = VisionNativeBridge.match(screenBitmap)
                    val match = results.firstOrNull { it.id == region.id }
                    
                    if (match != null && match.matched && match.score >= node.threshold) {
                        val cx = match.x + match.width / 2f
                        val cy = match.y + match.height / 2f
                        Log.d(TAG, "Region ${region.id} matched at ($cx, $cy) score: ${match.score}")
                        
                        context.put("${node.outputContextKey}_found", true)
                        context.put("${node.outputContextKey}_x", cx)
                        context.put("${node.outputContextKey}_y", cy)
                        context.put("${node.outputContextKey}_width", match.width)
                        context.put("${node.outputContextKey}_height", match.height)
                        context.put("${node.outputContextKey}_score", match.score)
                        context.put(node.outputContextKey, "${cx},${cy}")
                        
                        if (preset.executionMode != ExecutionMode.DETECT_ONLY) {
                            val success = VisionActionExecutor.execute(region.action, android.graphics.PointF(cx, cy))
                            if (!success) {
                                Log.w(TAG, "Failed to execute action for region ${region.id}")
                            }
                        } else {
                            Log.d(TAG, "DETECT_ONLY mode: Skipping action execution for region ${region.id}")
                        }
                        
                        // Add delay to let UI settle before next region
                        kotlinx.coroutines.delay(500)
                    } else {
                        Log.d(TAG, "Region ${region.id} not found above threshold")
                        context.put("${node.outputContextKey}_found", false)
                        
                        if (preset.executionMode == ExecutionMode.MANDATORY_SEQUENTIAL) {
                            return NodeResult.Failure("Mandatory region ${region.id} not found")
                        } else if (preset.executionMode == ExecutionMode.DETECT_ONLY) {
                            // In DETECT_ONLY, not finding a region is a valid path, let the engine handle routing via EdgeConditions
                            // We just log properties and move on.
                            Log.d(TAG, "DETECT_ONLY mode: Region ${region.id} not found. Continuing execution to allow Edge routing.")
                        }
                    }
                } finally {
                    templateBitmap.recycle()
                    VisionNativeBridge.nativeClearTemplates()
                }
            }
            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing back preset", e)
            return NodeResult.Failure("Malformed vision preset: ${e.message}")
        }
    }
}
