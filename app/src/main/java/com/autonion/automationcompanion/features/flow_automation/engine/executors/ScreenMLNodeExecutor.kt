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
import com.autonion.automationcompanion.features.screen_understanding_ml.core.AccessibilityAugmenter
import com.autonion.automationcompanion.features.screen_understanding_ml.core.HybridElementMatcher
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.logic.ActionExecutor
import kotlinx.serialization.json.Json
import android.graphics.PointF

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
            if (mlNode.mode == ScreenMLMode.UI_ATTRIBUTE) {
                return executeStepsA11yOnly(mlNode, context)
            } else {
                val provider = screenCaptureProvider 
                    ?: return NodeResult.Failure("Screen capture not available")
                return executeSteps(mlNode, context, provider)
            }
        }

        return when (mlNode.mode) {
            ScreenMLMode.OCR -> executeOCR(mlNode, context)
            ScreenMLMode.OBJECT_DETECTION -> executeObjectDetection(mlNode, context)
            ScreenMLMode.UI_ATTRIBUTE -> executeUIAttributeSearch(mlNode, context)
        }
    }

    private suspend fun executeOCR(node: ScreenMLNode, context: FlowContext): NodeResult {
        val provider = screenCaptureProvider
            ?: return NodeResult.Failure("Screen capture not available — MediaProjection not started")
        val ctx = appContext
            ?: return NodeResult.Failure("App context not available for OCR")

        // 1. Capture the screen
        val bitmap = provider.captureFrame()
            ?: return NodeResult.Failure("Failed to capture screen frame for OCR")

        // 2. Run ML Kit text recognition
        val ocrEngine = com.autonion.automationcompanion.features.screen_understanding_ml.core.OcrEngine()
        try {
            val result = ocrEngine.recognizeText(bitmap)
            Log.d(TAG, "OCR: recognized ${result.blocks.size} blocks, ${result.fullText.length} chars")
            DebugLogger.info(ctx, LogCategory.FLOW_BUILDER, "OCR Complete", "Recognized ${result.blocks.size} blocks, ${result.fullText.length} chars", TAG)

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
                    DebugLogger.success(ctx, LogCategory.FLOW_BUILDER, "OCR Target Found", "Text '${node.targetLabel}' found on screen", TAG)
                } else {
                    Log.d(TAG, "  ✗ Target text '${node.targetLabel}' not found")
                    DebugLogger.warning(ctx, LogCategory.FLOW_BUILDER, "OCR Target Missing", "Text '${node.targetLabel}' not found on screen", TAG)
                    return NodeResult.Failure("Target text '${node.targetLabel}' not found on screen")
                }
            }

            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "OCR recognition failed", e)
            DebugLogger.error(ctx, LogCategory.FLOW_BUILDER, "OCR Failed", "OCR error: ${e.message}", TAG)
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
            val detections = perceptionLayer.detectWithAccessibilityAugmentation(bitmap)
            Log.d(TAG, "Object Detection: detected ${detections.size} elements (YOLO + a11y)")
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
            val dm = ctx.resources.displayMetrics
            val screenW = dm.widthPixels.toFloat()
            val screenH = dm.heightPixels.toFloat()

            try {
                for (step in steps.sortedBy { it.orderIndex }) {
                    Log.d(TAG, "Executing step ${step.orderIndex}: ${step.label} (text=${step.anchor.text ?: "null"})")
                    
                    // Allow UI to settle
                    kotlinx.coroutines.delay(500)

                    val isOcrStep = step.anchor.label.equals("Text", ignoreCase = true)

                    val foundElement: com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement? = if (isOcrStep) {
                        // OCR text step — find text on current screen
                        findTextOnScreen(step, provider, screenW, screenH)
                    } else {
                        // ML element step — use hybrid matching with retry
                        findElementOnScreen(step, provider, perceptionLayer, screenW, screenH)
                    }

                    if (foundElement != null) {
                        val cx = (foundElement.bounds.left + foundElement.bounds.right) / 2f
                        val cy = (foundElement.bounds.top + foundElement.bounds.bottom) / 2f
                        Log.d(TAG, "Step ${step.orderIndex}: matched at ($cx, $cy), executing ${step.actionType}")
                        
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
                        
                        // Wait after action for screen to settle
                        kotlinx.coroutines.delay(1000)
                    } else {
                        Log.d(TAG, "Could not find element '${step.anchor.label}' (text=${step.anchor.text}) for step ${step.orderIndex}")
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
    /**
     * Find an ML-detected element (button, icon, etc.) on screen using multi-strategy matching.
     * Uses YOLO + accessibility augmented detection, then matches by:
     *   1. Text + label exact match (highest priority)
     *   2. Label + IoU spatial match
     *   3. Label + closest distance fallback
     * Retries for up to 5 seconds.
     */
    private suspend fun findElementOnScreen(
        step: com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep,
        provider: ScreenCaptureProvider,
        perceptionLayer: PerceptionLayer,
        screenW: Float,
        screenH: Float
    ): com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement? {
        val anchorBounds = step.anchor.bounds
        val anchorText = step.anchor.text
        val capW = step.captureScreenWidth
        val capH = step.captureScreenHeight

        // Pre-compute normalized anchor for resolution-independent matching
        val useNormalized = capW > 0f && capH > 0f
        val normalizedAnchor: android.graphics.RectF? = if (useNormalized) {
            android.graphics.RectF(
                anchorBounds.left / capW, anchorBounds.top / capH,
                anchorBounds.right / capW, anchorBounds.bottom / capH
            )
        } else null

        val anchorCx = (anchorBounds.left + anchorBounds.right) / 2f
        val anchorCy = (anchorBounds.top + anchorBounds.bottom) / 2f
        // Normalized anchor center for distance comparison across resolutions
        val normAnchorCx = if (capW > 0) anchorCx / capW else anchorCx
        val normAnchorCy = if (capH > 0) anchorCy / capH else anchorCy

        Log.d(TAG, "findElement: label=${step.anchor.label}, text=$anchorText, " +
                "bounds=$anchorBounds, captureSize=${capW}x${capH}, screen=${screenW}x${screenH}")

        val timeout = 5000L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeout) {
            val bitmap = provider.captureFrame() ?: run {
                kotlinx.coroutines.delay(300)
                continue
            }

            val detections = perceptionLayer.detectWithAccessibilityAugmentation(bitmap)
            val curW = bitmap.width.toFloat()
            val curH = bitmap.height.toFloat()

            // Filter by matching label
            val sameLabel = detections.filter { it.label.equals(step.anchor.label, ignoreCase = true) }
            Log.d(TAG, "findElement: ${detections.size} detections, ${sameLabel.size} match label '${step.anchor.label}'")

            if (sameLabel.isEmpty()) {
                kotlinx.coroutines.delay(300)
                continue
            }

            // ── Strategy 1: Text + label match (highest priority when text is present) ──
            if (!anchorText.isNullOrBlank()) {
                val textMatches = sameLabel.filter { el ->
                    HybridElementMatcher.isTextMatching(el.text, anchorText)
                }
                if (textMatches.isNotEmpty()) {
                    // Among text matches, pick the one closest to original position
                    val best = textMatches.minByOrNull { el ->
                        normalizedDistance(el, curW, curH, normAnchorCx, normAnchorCy)
                    }!!
                    Log.d(TAG, "findElement: TEXT match '${best.text}' at ${best.bounds} (source=${best.source ?: "yolo"})")
                    return best
                }
                // HARD GATE: If anchor text was captured, do NOT fall through to spatial/distance matches
            } else {
                // ── Strategy 2: Label + IoU spatial match (for textless elements) ──
                val iouScored = sameLabel.map { el ->
                    val iou = if (useNormalized && normalizedAnchor != null) {
                        val nEl = android.graphics.RectF(
                            el.bounds.left / curW, el.bounds.top / curH,
                            el.bounds.right / curW, el.bounds.bottom / curH
                        )
                        calculateIoU(nEl, normalizedAnchor)
                    } else {
                        calculateIoU(el.bounds, anchorBounds)
                    }
                    Pair(el, iou)
                }
                val bestIoU = iouScored.maxByOrNull { it.second }
                if (bestIoU != null && bestIoU.second > 0.1f) {
                    Log.d(TAG, "findElement: IoU match '${bestIoU.first.label}' IoU=${bestIoU.second} at ${bestIoU.first.bounds} (source=${bestIoU.first.source ?: "yolo"})")
                    return bestIoU.first
                }

                // ── Strategy 3: Closest distance fallback (for textless elements) ──
                val closest = sameLabel.minByOrNull { el ->
                    normalizedDistance(el, curW, curH, normAnchorCx, normAnchorCy)
                }
                if (closest != null) {
                    val dist = normalizedDistance(closest, curW, curH, normAnchorCx, normAnchorCy)
                    // Accept if within 30% of screen diagonal
                    if (dist < 0.3f) {
                        Log.d(TAG, "findElement: DISTANCE match '${closest.label}' dist=$dist at ${closest.bounds} (source=${closest.source ?: "yolo"})")
                        return closest
                    }
                }
            }

            kotlinx.coroutines.delay(300)
        }
        return null
    }

    /** Compute normalized distance between an element's center and an anchor point */
    private fun normalizedDistance(
        el: com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement,
        curW: Float, curH: Float,
        normAnchorCx: Float, normAnchorCy: Float
    ): Float {
        val cx = (el.bounds.left + el.bounds.right) / 2f
        val cy = (el.bounds.top + el.bounds.bottom) / 2f
        val normCx = if (curW > 0) cx / curW else cx
        val normCy = if (curH > 0) cy / curH else cy
        val dx = normCx - normAnchorCx
        val dy = normCy - normAnchorCy
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }


    /**
     * Find OCR text on the current screen. Uses live OCR + accessibility tree fallback.
     */
    private suspend fun findTextOnScreen(
        step: com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep,
        provider: ScreenCaptureProvider,
        screenW: Float,
        screenH: Float
    ): com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement? {
        val targetText = step.anchor.text
        if (targetText.isNullOrBlank()) {
            // No text stored — fall back to saved anchor coordinates
            Log.d(TAG, "findTextOnScreen: OCR step without text — using saved anchor coords")
            return step.anchor
        }

        Log.d(TAG, "findTextOnScreen: searching for '$targetText'")
        val timeout = 5000L
        val startTime = System.currentTimeMillis()
        val ocrEngine = com.autonion.automationcompanion.features.screen_understanding_ml.core.OcrEngine()

        try {
            while (System.currentTimeMillis() - startTime < timeout) {
                val bitmap = provider.captureFrame()
                if (bitmap != null) {
                    val result = ocrEngine.recognizeText(bitmap)

                    // Strategy 1: Line-level match within blocks
                    for (block in result.blocks) {
                        for (line in block.lines) {
                            if (HybridElementMatcher.isTextMatching(line.text, targetText)) {
                                val bounds = line.bounds ?: block.bounds
                                if (bounds != null) {
                                    Log.d(TAG, "findTextOnScreen: LINE match '${line.text}' at $bounds")
                                    return com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement(
                                        id = java.util.UUID.randomUUID().toString(),
                                        label = "Text",
                                        confidence = line.confidence ?: block.confidence ?: 0.9f,
                                        bounds = bounds,
                                        text = line.text
                                    )
                                }
                            }
                        }
                    }

                    // Strategy 2: Block-level match
                    val matchBlock = result.blocks.firstOrNull { block ->
                        HybridElementMatcher.isTextMatching(block.text, targetText)
                    }
                    if (matchBlock != null && matchBlock.bounds != null) {
                        Log.d(TAG, "findTextOnScreen: BLOCK match '${matchBlock.text}' at ${matchBlock.bounds}")
                        return com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement(
                            id = java.util.UUID.randomUUID().toString(),
                            label = "Text",
                            confidence = matchBlock.confidence ?: 0.9f,
                            bounds = matchBlock.bounds,
                            text = matchBlock.text
                        )
                    }

                    // Strategy 3: Reverse containment
                    val reverseMatch = result.blocks.firstOrNull { block ->
                        block.text.length >= 3 && HybridElementMatcher.isTextMatching(targetText, block.text)
                    }
                    if (reverseMatch != null && reverseMatch.bounds != null) {
                        Log.d(TAG, "findTextOnScreen: REVERSE match '${reverseMatch.text}' at ${reverseMatch.bounds}")
                        return com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement(
                            id = java.util.UUID.randomUUID().toString(),
                            label = "Text",
                            confidence = (reverseMatch.confidence ?: 0.9f) * 0.8f,
                            bounds = reverseMatch.bounds,
                            text = reverseMatch.text
                        )
                    }
                }

                // Strategy 4: Accessibility tree fallback
                try {
                    val elements = AccessibilityAugmenter.captureAllInteractiveElements()
                    val match = elements.firstOrNull { el ->
                        HybridElementMatcher.isTextMatching(el.text, targetText)
                    }
                    if (match != null) {
                        Log.d(TAG, "findTextOnScreen: A11Y match for '$targetText' at ${match.bounds}")
                        return com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement(
                            id = java.util.UUID.randomUUID().toString(),
                            label = "Text",
                            confidence = 0.85f,
                            bounds = match.bounds,
                            text = match.text
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Accessibility text search failed: ${e.message}")
                }

                kotlinx.coroutines.delay(500)
            }
        } catch (e: Exception) {
            Log.e(TAG, "findTextOnScreen failed", e)
        } finally {
            ocrEngine.close()
        }
        Log.w(TAG, "findTextOnScreen: '$targetText' not found within timeout")
        return null
    }

    private suspend fun executeStepsA11yOnly(node: ScreenMLNode, context: FlowContext): NodeResult {
        val ctx = appContext ?: return NodeResult.Failure("App context not available")
        
        try {
            val steps = Json.decodeFromString<List<AutomationStep>>(node.automationStepsJson)
            Log.d(TAG, "Playing back ${steps.size} A11y-only automation steps")
            DebugLogger.info(ctx, LogCategory.FLOW_BUILDER, "A11y Steps Started", "Playing back ${steps.size} automation steps via Accessibility", TAG)

            for (step in steps.sortedBy { it.orderIndex }) {
                Log.d(TAG, "Executing A11y step ${step.orderIndex}: ${step.label} (text=${step.anchor.text ?: "null"})")
                
                // Allow UI to settle
                kotlinx.coroutines.delay(500)

                val foundElement = findElementViaA11y(step, timeout = 5000L)

                if (foundElement != null) {
                    val cx = (foundElement.bounds.left + foundElement.bounds.right) / 2f
                    val cy = (foundElement.bounds.top + foundElement.bounds.bottom) / 2f
                    Log.d(TAG, "Step ${step.orderIndex}: matched via A11y at ($cx, $cy), executing ${step.actionType}")
                    
                    val intent = ActionIntent(
                        type = step.actionType,
                        targetPoint = PointF(cx, cy),
                        inputText = step.inputText,
                        description = step.label
                    )
                    
                    val success = ActionExecutor.execute(ctx, intent)
                    if (!success && !step.isOptional) {
                        return NodeResult.Failure("Failed to execute action for step ${step.label}")
                    }
                    
                    kotlinx.coroutines.delay(1000)
                } else {
                    Log.d(TAG, "Could not find element '${step.anchor.label}' (text=${step.anchor.text}) via A11y for step ${step.orderIndex}")
                    if (!step.isOptional) {
                        return NodeResult.Failure("Mandatory element '${step.anchor.label}' not found via accessibility")
                    }
                }
            }
            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing back A11y automation steps", e)
            DebugLogger.error(ctx, LogCategory.FLOW_BUILDER, "A11y Steps Failed", "Error: ${e.message}", TAG)
            return NodeResult.Failure("Malformed A11y steps: ${e.message}")
        }
    }

    private suspend fun findElementViaA11y(step: AutomationStep, timeout: Long = 5000L): UIElement? {
        val startTime = System.currentTimeMillis()
        val anchorText = step.anchor.text

        while (System.currentTimeMillis() - startTime < timeout) {
            val elements = AccessibilityAugmenter.captureAllInteractiveElements()

            // Strategy 1: Text match
            if (!anchorText.isNullOrBlank()) {
                val textMatch = elements.firstOrNull { el ->
                    HybridElementMatcher.isTextMatching(el.text, anchorText)
                }
                if (textMatch != null) return textMatch
                // HARD GATE: If anchor text was captured, do NOT fall through to position match
            } else {
                // Strategy 2: Label + closest position match (for textless elements)
                val sameLabel = elements.filter { it.label.equals(step.anchor.label, ignoreCase = true) }
                if (sameLabel.isNotEmpty()) {
                    val closest = sameLabel.minByOrNull { el ->
                        val dx = (el.bounds.centerX() - step.anchor.bounds.centerX())
                        val dy = (el.bounds.centerY() - step.anchor.bounds.centerY())
                        dx * dx + dy * dy
                    }
                    if (closest != null) return closest
                }
            }

            kotlinx.coroutines.delay(300)
        }
        return null
    }

    private suspend fun executeUIAttributeSearch(node: ScreenMLNode, context: FlowContext): NodeResult {
        val ctx = appContext ?: return NodeResult.Failure("App context not available")
        val elements = AccessibilityAugmenter.captureAllInteractiveElements()

        val json = elements.joinToString(";") { el ->
            "${el.label}:${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom}:${el.confidence}"
        }
        context.put(node.outputContextKey, json)
        context.put("${node.outputContextKey}_success", true)
        context.put("${node.outputContextKey}_element_count", elements.size)

        if (node.targetLabel != null) {
            val target = elements.find { it.label.equals(node.targetLabel, ignoreCase = true) }
                ?: elements.find { it.text?.contains(node.targetLabel, ignoreCase = true) == true }

            if (target != null) {
                val cx = (target.bounds.left + target.bounds.right) / 2f
                val cy = (target.bounds.top + target.bounds.bottom) / 2f
                context.put("${node.outputContextKey}_target_found", true)
                context.put("${node.outputContextKey}_target_x", cx)
                context.put("${node.outputContextKey}_target_y", cy)
                DebugLogger.success(ctx, LogCategory.FLOW_BUILDER, "Target Found", "'${node.targetLabel}' found via accessibility at ($cx, $cy)", TAG)
            } else {
                context.put("${node.outputContextKey}_target_found", false)
                DebugLogger.warning(ctx, LogCategory.FLOW_BUILDER, "Target Missing", "'${node.targetLabel}' not found via accessibility", TAG)
                return NodeResult.Failure("Target '${node.targetLabel}' not found via accessibility")
            }
        }
        return NodeResult.Success
    }

    // ─── Helpers ─────────────────────────────────────────────────────────


    private fun calculateIoU(a: android.graphics.RectF, b: android.graphics.RectF): Float {
        val iL = maxOf(a.left, b.left); val iT = maxOf(a.top, b.top)
        val iR = minOf(a.right, b.right); val iB = minOf(a.bottom, b.bottom)
        if (iR < iL || iB < iT) return 0f
        val iA = (iR - iL) * (iB - iT)
        val uA = a.width() * a.height() + b.width() * b.height() - iA
        return if (uA > 0) iA / uA else 0f
    }
}
