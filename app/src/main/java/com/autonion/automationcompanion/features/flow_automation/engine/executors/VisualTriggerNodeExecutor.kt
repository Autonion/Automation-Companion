package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.util.Log
import android.os.SystemClock
import com.autonion.automationcompanion.core.vision.MatchResultNative
import com.autonion.automationcompanion.core.vision.TapBounds
import com.autonion.automationcompanion.core.vision.predictMovingTap
import com.autonion.automationcompanion.features.visual_trigger.models.VisionMatchMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.autonion.automationcompanion.core.vision.VisionNativeBridge
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.engine.ScreenCaptureProvider
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.VisualTriggerNode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset
import com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode
import com.autonion.automationcompanion.features.visual_trigger.models.TapDispatchMode
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import com.autonion.automationcompanion.features.visual_trigger.models.VisionRegion
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
            VisionNativeBridge.clearTemplates()
            VisionNativeBridge.addTemplate(
                templateId,
                templateBitmap,
                vtNode.searchRegionX,
                vtNode.searchRegionY,
                vtNode.searchRegionWidth,
                vtNode.searchRegionHeight,
                vtNode.threshold,
                allowFullscreenFallback = false,
                trackRoiToMatch = false
            )

            // 4. Run native template matching
            val results = VisionNativeBridge.match(screenBitmap)

            // 5. Find our template result
            val match = results.firstOrNull { it.id == templateId }

            if (match != null && match.matched) {
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
            screenBitmap.recycle()
            VisionNativeBridge.clearTemplates()
        }
    }

    private suspend fun executePreset(node: VisualTriggerNode, provider: ScreenCaptureProvider, context: FlowContext): NodeResult {
        try {
            val preset = kotlinx.serialization.json.Json.decodeFromString<VisionPreset>(node.visionPresetJson)
            Log.d(TAG, "Playing back VisionPreset: ${preset.name} with ${preset.regions.size} regions, mode: ${preset.executionMode}")

            if (preset.executionMode == ExecutionMode.DETECT_ONLY) {
                if (preset.regions.any { it.matchMode == VisionMatchMode.MOVING }) {
                    return executeMovingPreset(node, preset, provider, context)
                }
                return executeDetectOnlyPreset(node, preset, provider, context)
            }
            
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
                    VisionNativeBridge.clearTemplates()
                    val searchRect = region.toSearchRect()
                    VisionNativeBridge.addTemplate(
                        region.id,
                        templateBitmap,
                        searchRect.left,
                        searchRect.top,
                        searchRect.width(),
                        searchRect.height(),
                        region.matchThreshold,
                        allowFullscreenFallback = region.customSearchRect() == null,
                        trackRoiToMatch = region.customSearchRect() == null
                    )
                    val results = VisionNativeBridge.match(screenBitmap)
                    val match = results.firstOrNull { it.id == region.id }
                    
                    if (match != null && match.matched) {
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
                        
                        val success = VisionActionExecutor.execute(region.action, android.graphics.PointF(cx, cy))
                        if (!success) {
                            Log.w(TAG, "Failed to execute action for region ${region.id}")
                        }
                        
                        // Add delay to let UI settle before next region
                        kotlinx.coroutines.delay(500)
                    } else {
                        val score = match?.score ?: 0f
                        Log.d(TAG, "Region ${region.id} not found above threshold (score=$score, need≥${region.matchThreshold})")
                        context.put("${node.outputContextKey}_found", false)
                        
                        if (preset.executionMode == ExecutionMode.MANDATORY_SEQUENTIAL) {
                            return NodeResult.Failure("Mandatory region ${region.id} not found")
                        }
                        // OPTIONAL_SEQUENTIAL: continue to next region
                    }
                } finally {
                    templateBitmap.recycle()
                    screenBitmap.recycle()
                    VisionNativeBridge.clearTemplates()
                }
            }
            
            return NodeResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing back preset", e)
            return NodeResult.Failure("Malformed vision preset: ${e.message}")
        }
    }

    private suspend fun executeDetectOnlyPreset(
        node: VisualTriggerNode,
        preset: VisionPreset,
        provider: ScreenCaptureProvider,
        context: FlowContext
    ): NodeResult {
        val loadedTemplates = mutableListOf<Pair<VisionRegion, Bitmap>>()
        var screenBitmap: Bitmap? = null

        try {
            preset.regions.forEach { region ->
                val templateBitmap = BitmapFactory.decodeFile(region.templatePath)
                if (templateBitmap == null) {
                    Log.e(TAG, "Failed to decode region template: ${region.templatePath}")
                } else {
                    loadedTemplates.add(region to templateBitmap)
                }
            }

            if (loadedTemplates.isEmpty()) {
                context.put("${node.outputContextKey}_found", false)
                return NodeResult.Failure("No region templates could be decoded")
            }

            kotlinx.coroutines.delay(200)

            screenBitmap = provider.captureFrame()
                ?: return NodeResult.Failure("Failed to capture screen frame")

            VisionNativeBridge.clearTemplates()
            loadedTemplates.forEach { (region, templateBitmap) ->
                val searchRect = region.toSearchRect()
                VisionNativeBridge.addTemplate(
                    region.id,
                    templateBitmap,
                    searchRect.left,
                    searchRect.top,
                    searchRect.width(),
                    searchRect.height(),
                    region.matchThreshold,
                    allowFullscreenFallback = false,
                    trackRoiToMatch = false
                )
            }

            val results = VisionNativeBridge.match(screenBitmap)
            val matchesById = results.filter { it.matched }.groupBy { it.id }
            val matchedActions = mutableListOf<Pair<VisionRegion, PointF>>()

            preset.regions.forEach { region ->
                val matches = matchesById[region.id] ?: return@forEach
                matches.forEach { match ->
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

                    matchedActions.add(region to PointF(cx, cy))
                }
            }

            if (matchedActions.isEmpty()) {
                context.put("${node.outputContextKey}_found", false)
                context.put(node.outputContextKey, "not_found")
                Log.d(TAG, "DETECT_ONLY: no regions matched")
                return NodeResult.Failure("No regions matched in DETECT_ONLY mode")
            }
            context.put("${node.outputContextKey}_count", matchedActions.size)

            val sequentialActions = matchedActions.toMutableList()
            val concurrentTaps = matchedActions.filter { (region, _) ->
                region.action is VisionAction.Click
            }

            if (preset.tapDispatchMode == TapDispatchMode.CONCURRENT && concurrentTaps.size > 1) {
                val maxConcurrentTaps = VisionActionExecutor.maxConcurrentTapCount()
                val dispatchTaps = concurrentTaps.take(maxConcurrentTaps)
                val overflowCount = concurrentTaps.size - dispatchTaps.size
                val details = dispatchTaps.joinToString { (region, point) ->
                    "#${region.id}(${point.x},${point.y})"
                }
                if (overflowCount > 0) {
                    Log.w(
                        TAG,
                        "Concurrent tap capped at $maxConcurrentTaps; dropped $overflowCount taps for this flow run"
                    )
                }
                Log.d(TAG, "Executing concurrent tap for flow node: $details")
                val success = VisionActionExecutor.executeMultiTap(dispatchTaps.map { (_, point) -> point })
                if (success) {
                    val concurrentTapSet = concurrentTaps.toSet()
                    sequentialActions.removeAll { it in concurrentTapSet }
                    if (sequentialActions.isNotEmpty()) {
                        kotlinx.coroutines.delay(500)
                    }
                } else {
                    Log.w(TAG, "Concurrent tap failed; falling back to sequential region actions")
                }
            }

            for ((region, point) in sequentialActions) {
                val success = VisionActionExecutor.execute(region.action, point)
                if (!success) {
                    Log.w(TAG, "Failed to execute action for region ${region.id}")
                }
                kotlinx.coroutines.delay(500)
            }

            return NodeResult.Success
        } finally {
            loadedTemplates.forEach { (_, templateBitmap) ->
                if (!templateBitmap.isRecycled) templateBitmap.recycle()
            }
            screenBitmap?.let { if (!it.isRecycled) it.recycle() }
            VisionNativeBridge.clearTemplates()
        }
    }

    private suspend fun executeMovingPreset(
        node: VisualTriggerNode, preset: VisionPreset,
        provider: ScreenCaptureProvider, context: FlowContext
    ): NodeResult {
        if (preset.regions.any { it.matchMode == VisionMatchMode.MOVING &&
                (it.customSearchRect() == null || it.action !is VisionAction.Click) }) {
            return NodeResult.Failure("Moving objects require a search area and Tap action")
        }
        VisionNativeBridge.clearTemplates()
        try {
            preset.regions.forEach { region ->
                val bitmap = BitmapFactory.decodeFile(region.templatePath)
                    ?: return NodeResult.Failure("Cannot load target ${region.id}")
                try {
                    val roi = region.toSearchRect()
                    VisionNativeBridge.addTemplate(region.id, bitmap, roi.left, roi.top, roi.width(), roi.height(),
                        region.effectiveThreshold, allowFullscreenFallback = false, trackRoiToMatch = false,
                        moving = region.matchMode == VisionMatchMode.MOVING)
                } finally { bitmap.recycle() }
            }
            val deadline = SystemClock.uptimeMillis() + 3000
            val byId = preset.regions.associateBy { it.id }
            while (SystemClock.uptimeMillis() < deadline) {
                val sample = provider.withLatestFrame((deadline - SystemClock.uptimeMillis()).coerceAtLeast(1)) { frame ->
                    val plane = frame.image.planes[0]
                    require(plane.pixelStride == 4)
                    val hits = VisionNativeBridge.matchRgba(plane.buffer, frame.width, frame.height, plane.rowStride, frame.acquiredAtMs)
                    Triple(hits, frame.acquiredAtMs, frame.width to frame.height)
                } ?: break
                val hits = sample.first.filter { it.matched }
                val batchSize = if (preset.tapDispatchMode == TapDispatchMode.CONCURRENT) VisionActionExecutor.maxConcurrentTapCount() else 1
                var tapped = 0
                for (batch in hits.chunked(batchSize)) {
                    withContext(Dispatchers.Main.immediate) {
                        val taps = batch.mapNotNull { hit ->
                            val region = byId.getValue(hit.id)
                            if (region.matchMode == VisionMatchMode.MOVING) {
                                val roi = region.toSearchRect()
                                val point = predictMovingTap(hit, sample.second, SystemClock.uptimeMillis(),
                                    TapBounds(maxOf(0, roi.left), maxOf(0, roi.top), minOf(sample.third.first, roi.right), minOf(sample.third.second, roi.bottom)),
                                    region.tapLeadMs) ?: return@mapNotNull null
                                hit to PointF(point.x, point.y)
                            } else if (region.action is VisionAction.Click) {
                                hit to PointF(hit.x + hit.width / 2f, hit.y + hit.height / 2f)
                            } else null
                        }
                        if (taps.isNotEmpty() && VisionActionExecutor.executeMultiTap(taps.map { it.second }, 32)) {
                            tapped += taps.size
                            val (hit, point) = taps.first()
                            context.put("${node.outputContextKey}_x", point.x)
                            context.put("${node.outputContextKey}_y", point.y)
                            context.put("${node.outputContextKey}_width", hit.width)
                            context.put("${node.outputContextKey}_height", hit.height)
                            context.put("${node.outputContextKey}_score", hit.score)
                            context.put(node.outputContextKey, "${point.x},${point.y}")
                        }
                    }
                }
                for (hit in hits.filter { byId.getValue(it.id).let { region ->
                        region.matchMode == VisionMatchMode.STATIC && region.action !is VisionAction.Click } }) {
                    val region = byId.getValue(hit.id)
                    val point = PointF(hit.x + hit.width / 2f, hit.y + hit.height / 2f)
                    if (VisionActionExecutor.execute(region.action, point)) tapped++
                }
                if (tapped > 0) {
                    context.put("${node.outputContextKey}_found", true)
                    context.put("${node.outputContextKey}_count", tapped)
                    return NodeResult.Success
                }
                kotlinx.coroutines.delay(16)
            }
            context.put("${node.outputContextKey}_found", false)
            context.put(node.outputContextKey, "not_found")
            return NodeResult.Failure("No fresh moving target verified within 3 seconds")
        } finally {
            VisionNativeBridge.clearTemplates()
        }
    }
}
