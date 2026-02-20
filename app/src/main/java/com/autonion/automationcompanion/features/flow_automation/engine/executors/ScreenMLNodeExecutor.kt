package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.engine.ScreenCaptureProvider
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.ScreenMLMode
import com.autonion.automationcompanion.features.flow_automation.model.ScreenMLNode
import com.autonion.automationcompanion.features.screen_understanding_ml.core.PerceptionLayer

private const val TAG = "ScreenMLNodeExecutor"

/**
 * Executor for [ScreenMLNode].
 *
 * Captures the current screen via [ScreenCaptureProvider] and runs
 * TFLite-based inference through [PerceptionLayer]:
 * - **OCR mode**: Collects all detected `UIElement.text` values and
 *   writes concatenated text to [FlowContext].
 * - **Object Detection mode**: Finds `UIElement` matching `targetLabel`
 *   and writes bounding-box coordinates to [FlowContext].
 */
class ScreenMLNodeExecutor(
    private val appContext: Context? = null,
    private val screenCaptureProvider: ScreenCaptureProvider? = null
) : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val mlNode = node as? ScreenMLNode
            ?: return NodeResult.Failure("Expected ScreenMLNode but got ${node::class.simpleName}")

        Log.d(TAG, "Screen ML: mode=${mlNode.mode}, outputKey=${mlNode.outputContextKey}")

        if (mlNode.automationStepsJson.isNotEmpty()) {
            val provider = screenCaptureProvider 
                ?: return NodeResult.Failure("Screen capture not available")
            return executeSteps(mlNode, context, provider)
        }

        return when (mlNode.mode) {
            ScreenMLMode.OCR -> executeOCR(mlNode, context)
            ScreenMLMode.OBJECT_DETECTION -> executeObjectDetection(mlNode, context)
        }
    }

    private suspend fun executeOCR(node: ScreenMLNode, context: FlowContext): NodeResult {
        val provider = screenCaptureProvider
            ?: return NodeResult.Failure("Screen capture not available — MediaProjection not started")
        val ctx = appContext
            ?: return NodeResult.Failure("App context not available for PerceptionLayer")

        // 1. Capture the screen
        val bitmap = provider.captureFrame()
            ?: return NodeResult.Failure("Failed to capture screen frame for OCR")

        // 2. Run TFLite detection
        val perceptionLayer = PerceptionLayer(ctx)
        try {
            val detections = perceptionLayer.detect(bitmap)
            Log.d(TAG, "OCR: detected ${detections.size} elements")

            // 3. Collect all text from detected elements
            val allText = detections
                .mapNotNull { it.text }
                .filter { it.isNotBlank() }
                .joinToString(" ")

            // Also collect labels for context
            val labels = detections.map { it.label }.distinct()

            context.put(node.outputContextKey, allText)
            context.put("${node.outputContextKey}_success", true)
            context.put("${node.outputContextKey}_element_count", detections.size)
            context.put("${node.outputContextKey}_labels", labels.joinToString(","))

            Log.d(TAG, "OCR result: ${detections.size} elements, text='${allText.take(100)}'")
            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "OCR detection failed", e)
            context.put("${node.outputContextKey}_success", false)
            return NodeResult.Failure("OCR detection error: ${e.message}")
        } finally {
            perceptionLayer.close()
        }
    }

    private suspend fun executeObjectDetection(node: ScreenMLNode, context: FlowContext): NodeResult {
        val provider = screenCaptureProvider
            ?: return NodeResult.Failure("Screen capture not available — MediaProjection not started")
        val ctx = appContext
            ?: return NodeResult.Failure("App context not available for PerceptionLayer")

        // 1. Capture the screen
        val bitmap = provider.captureFrame()
            ?: return NodeResult.Failure("Failed to capture screen frame for Object Detection")

        // 2. Run TFLite detection
        val perceptionLayer = PerceptionLayer(ctx)
        try {
            val detections = perceptionLayer.detect(bitmap)
            Log.d(TAG, "Object Detection: detected ${detections.size} elements")

            // 3. Serialize all detection results
            val detectionsJson = detections.joinToString(";") { el ->
                "${el.label}:${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom}:${el.confidence}"
            }
            context.put(node.outputContextKey, detectionsJson)
            context.put("${node.outputContextKey}_success", true)
            context.put("${node.outputContextKey}_element_count", detections.size)

            // 4. If targetLabel specified, check for its presence
            if (node.targetLabel != null) {
                val target = detections.find {
                    it.label.equals(node.targetLabel, ignoreCase = true)
                }

                if (target != null) {
                    val cx = (target.bounds.left + target.bounds.right) / 2f
                    val cy = (target.bounds.top + target.bounds.bottom) / 2f
                    context.put("${node.outputContextKey}_target_found", true)
                    context.put("${node.outputContextKey}_target_x", cx)
                    context.put("${node.outputContextKey}_target_y", cy)
                    context.put("${node.outputContextKey}_target_label", target.label)
                    context.put("${node.outputContextKey}_target_confidence", target.confidence)
                    Log.d(TAG, "  ✓ Target '${node.targetLabel}' found at ($cx, $cy), confidence=${target.confidence}")
                } else {
                    context.put("${node.outputContextKey}_target_found", false)
                    Log.d(TAG, "  ✗ Target '${node.targetLabel}' not found among ${detections.size} detections")
                    return NodeResult.Failure("Target element '${node.targetLabel}' not found on screen")
                }
            }

            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Object detection failed", e)
            context.put("${node.outputContextKey}_success", false)
            return NodeResult.Failure("Object detection error: ${e.message}")
        } finally {
            perceptionLayer.close()
        }
    }

    private suspend fun executeSteps(node: ScreenMLNode, context: FlowContext, provider: ScreenCaptureProvider): NodeResult {
        val ctx = appContext ?: return NodeResult.Failure("App context not available for PerceptionLayer")
        
        try {
            val steps = kotlinx.serialization.json.Json.decodeFromString<List<com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep>>(node.automationStepsJson)
            Log.d(TAG, "Playing back ${steps.size} ML automation steps")
            
            val perceptionLayer = PerceptionLayer(ctx)
            try {
                for (step in steps.sortedBy { it.orderIndex }) {
                    Log.d(TAG, "Executing step ${step.orderIndex}: ${step.label}")
                    
                    // Allow UI to settle
                    kotlinx.coroutines.delay(500)
                    
                    val bitmap = provider.captureFrame()
                    if (bitmap == null) {
                        if (step.isOptional) continue else return NodeResult.Failure("Failed to capture screen for step ${step.label}")
                    }
                    
                    val detections = perceptionLayer.detect(bitmap)
                    val matches = detections.filter { it.label.equals(step.anchor.label, ignoreCase = true) }
                    
                    val originalCx = (step.anchor.bounds.left + step.anchor.bounds.right) / 2f
                    val originalCy = (step.anchor.bounds.top + step.anchor.bounds.bottom) / 2f
                    
                    val bestMatch = matches.minByOrNull { detection ->
                        val cx = (detection.bounds.left + detection.bounds.right) / 2f
                        val cy = (detection.bounds.top + detection.bounds.bottom) / 2f
                        Math.hypot((cx - originalCx).toDouble(), (cy - originalCy).toDouble())
                    }
                    
                    if (bestMatch != null) {
                        val cx = (bestMatch.bounds.left + bestMatch.bounds.right) / 2f
                        val cy = (bestMatch.bounds.top + bestMatch.bounds.bottom) / 2f
                        
                        val intent = com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent(
                            type = step.actionType,
                            targetPoint = android.graphics.PointF(cx, cy),
                            inputText = step.inputText,
                            description = step.label
                        )
                        
                        val success = com.autonion.automationcompanion.features.screen_understanding_ml.logic.ActionExecutor.execute(ctx, intent)
                        if (!success && !step.isOptional) {
                            return NodeResult.Failure("Failed to execute action for step ${step.label}")
                        }
                    } else {
                        Log.d(TAG, "Could not find element '${step.anchor.label}' for step ${step.orderIndex}")
                        if (!step.isOptional) {
                            return NodeResult.Failure("Mandatory element '${step.anchor.label}' not found")
                        }
                    }
                }
            } finally {
                perceptionLayer.close()
            }
            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing back ML automation steps", e)
            return NodeResult.Failure("Malformed ML steps: ${e.message}")
        }
    }
}
