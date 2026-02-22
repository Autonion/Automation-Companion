package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.content.Context
import android.util.Log
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
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

        // 1. Capture the screen
        val bitmap = provider.captureFrame()
            ?: return NodeResult.Failure("Failed to capture screen frame for OCR")

        // 2. Run ML Kit text recognition
        val ocrEngine = com.autonion.automationcompanion.features.screen_understanding_ml.core.OcrEngine()
        try {
            val result = ocrEngine.recognizeText(bitmap)
            Log.d(TAG, "OCR: recognized ${result.blocks.size} blocks, ${result.fullText.length} chars")
            DebugLogger.info(appContext!!, LogCategory.FLOW_BUILDER, "OCR Complete", "Recognized ${result.blocks.size} blocks, ${result.fullText.length} chars", TAG)

            // 3. Write results to FlowContext
            context.put(node.outputContextKey, result.fullText)
            context.put("${node.outputContextKey}_success", true)
            context.put("${node.outputContextKey}_block_count", result.blocks.size)

            // Write individual block texts for fine-grained access
            result.blocks.forEachIndexed { i, block ->
                context.put("${node.outputContextKey}_block_${i}", block.text)
                block.bounds?.let { b ->
                    context.put("${node.outputContextKey}_block_${i}_bounds",
                        "${b.left},${b.top},${b.right},${b.bottom}")
                }
            }

            Log.d(TAG, "OCR result: '${result.fullText.take(200)}'")

            // 4. If targetLabel set, search for it in recognized text
            if (!node.targetLabel.isNullOrBlank()) {
                val found = result.fullText.contains(node.targetLabel, ignoreCase = true)
                context.put("${node.outputContextKey}_target_found", found)

                if (found) {
                    // Find the block containing the target text and write its position
                    val matchBlock = result.blocks.firstOrNull {
                        it.text.contains(node.targetLabel, ignoreCase = true)
                    }
                    matchBlock?.bounds?.let { b ->
                        val cx = (b.left + b.right) / 2f
                        val cy = (b.top + b.bottom) / 2f
                        context.put("${node.outputContextKey}_target_x", cx)
                        context.put("${node.outputContextKey}_target_y", cy)
                    }
                    Log.d(TAG, "  ✓ Target text '${node.targetLabel}' found")
                    DebugLogger.success(appContext!!, LogCategory.FLOW_BUILDER, "OCR Target Found", "Text '${node.targetLabel}' found on screen", TAG)
                } else {
                    Log.d(TAG, "  ✗ Target text '${node.targetLabel}' not found")
                    DebugLogger.warning(appContext!!, LogCategory.FLOW_BUILDER, "OCR Target Missing", "Text '${node.targetLabel}' not found on screen", TAG)
                    return NodeResult.Failure("Target text '${node.targetLabel}' not found on screen")
                }
            }

            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "OCR recognition failed", e)
            DebugLogger.error(appContext!!, LogCategory.FLOW_BUILDER, "OCR Failed", "OCR error: ${e.message}", TAG)
            context.put("${node.outputContextKey}_success", false)
            return NodeResult.Failure("OCR error: ${e.message}")
        } finally {
            ocrEngine.close()
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
            DebugLogger.info(ctx, LogCategory.FLOW_BUILDER, "Detection Complete", "Detected ${detections.size} UI elements", TAG)

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
                    DebugLogger.success(ctx, LogCategory.FLOW_BUILDER, "Target Found", "'${node.targetLabel}' found at ($cx,$cy) conf=${target.confidence}", TAG)
                } else {
                    context.put("${node.outputContextKey}_target_found", false)
                    Log.d(TAG, "  ✗ Target '${node.targetLabel}' not found among ${detections.size} detections")
                    DebugLogger.warning(ctx, LogCategory.FLOW_BUILDER, "Target Missing", "'${node.targetLabel}' not found among ${detections.size} detections", TAG)
                    return NodeResult.Failure("Target element '${node.targetLabel}' not found on screen")
                }
            }

            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Object detection failed", e)
            DebugLogger.error(ctx, LogCategory.FLOW_BUILDER, "Detection Failed", "Object detection error: ${e.message}", TAG)
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
            DebugLogger.info(ctx, LogCategory.FLOW_BUILDER, "ML Steps Started", "Playing back ${steps.size} automation steps", TAG)
            
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
            DebugLogger.error(ctx, LogCategory.FLOW_BUILDER, "ML Steps Failed", "Error: ${e.message}", TAG)
            return NodeResult.Failure("Malformed ML steps: ${e.message}")
        }
    }
}
