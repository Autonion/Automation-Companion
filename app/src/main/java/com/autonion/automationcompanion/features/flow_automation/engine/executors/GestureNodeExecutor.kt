package com.autonion.automationcompanion.features.flow_automation.engine.executors

import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.features.flow_automation.engine.NodeExecutor
import com.autonion.automationcompanion.features.flow_automation.engine.NodeResult
import com.autonion.automationcompanion.features.flow_automation.model.CoordinateSource
import com.autonion.automationcompanion.features.flow_automation.model.FlowContext
import com.autonion.automationcompanion.features.flow_automation.model.FlowNode
import com.autonion.automationcompanion.features.flow_automation.model.GestureNode
import com.autonion.automationcompanion.features.flow_automation.model.GestureType
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import com.autonion.automationcompanion.features.visual_trigger.models.ScrollDirection
import com.autonion.automationcompanion.features.visual_trigger.service.VisionActionExecutor

private const val TAG = "GestureNodeExecutor"

/**
 * Executor for [GestureNode]. Delegates gesture dispatch to the existing
 * [VisionActionExecutor] which uses the AccessibilityService.
 */
class GestureNodeExecutor : NodeExecutor {

    override suspend fun execute(node: FlowNode, context: FlowContext): NodeResult {
        val gestureNode = node as? GestureNode
            ?: return NodeResult.Failure("Expected GestureNode but got ${node::class.simpleName}")

        if (!VisionActionExecutor.isConnected()) {
            return NodeResult.Failure("AccessibilityService not connected")
        }

        if (gestureNode.recordedActionsJson.isNotEmpty()) {
            val result = executeRecordedActions(gestureNode.recordedActionsJson)
            if (result is NodeResult.Success) {
                return result
            }
            Log.w(TAG, "Recorded action playback failed, falling back to static config")
        }

        // Resolve coordinates
        val point = resolveCoordinates(gestureNode, context)
            ?: return NodeResult.Failure("Could not resolve coordinates")

        Log.d(TAG, "Executing ${gestureNode.gestureType} at (${point.x}, ${point.y})")

        // Map to VisionAction and execute
        val action = when (gestureNode.gestureType) {
            GestureType.TAP -> VisionAction.Click
            GestureType.LONG_PRESS -> VisionAction.LongClick
            GestureType.SWIPE -> {
                // Default swipe down if no end coordinates specified
                VisionAction.Scroll(ScrollDirection.DOWN)
            }
            GestureType.CUSTOM -> VisionAction.Click // Fallback
        }

        val success = VisionActionExecutor.execute(action, point)
        return if (success) {
            Log.d(TAG, "Gesture executed successfully")
            NodeResult.Success
        } else {
            Log.e(TAG, "Gesture dispatch failed")
            NodeResult.Failure("Gesture dispatch failed")
        }
    }

    private fun resolveCoordinates(node: GestureNode, context: FlowContext): PointF? {
        return when (val source = node.coordinateSource) {
            is CoordinateSource.Static -> PointF(source.x, source.y)
            is CoordinateSource.FromContext -> {
                // Try to read "x,y" string or a PointF from context
                val raw = context.get<Any>(source.key) ?: return null
                when (raw) {
                    is PointF -> raw
                    is String -> {
                        val parts = raw.split(",").map { it.trim().toFloatOrNull() }
                        if (parts.size == 2 && parts.all { it != null }) {
                            PointF(parts[0]!!, parts[1]!!)
                        } else null
                    }
                    is Pair<*, *> -> {
                        val x = (raw.first as? Number)?.toFloat() ?: return null
                        val y = (raw.second as? Number)?.toFloat() ?: return null
                        PointF(x, y)
                    }
                    else -> null
                }
            }
        }
    }

    private suspend fun executeRecordedActions(json: String): NodeResult {
        try {
            val actions = kotlinx.serialization.json.Json.decodeFromString<List<com.autonion.automationcompanion.features.gesture_recording_playback.models.Action>>(json)
            Log.d(TAG, "Playing back ${actions.size} recorded actions")
            
            for (action in actions) {
                if (action.delayBefore > 0) kotlinx.coroutines.delay(action.delayBefore)
                
                val success = when (action.type) {
                    com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.CLICK -> {
                        val pt = action.points.firstOrNull() ?: continue
                        val path = android.graphics.Path().apply { moveTo(pt.x, pt.y); lineTo(pt.x+1, pt.y+1) }
                        VisionActionExecutor.dispatchPath(path, if (action.duration < 50) 50 else action.duration)
                    }
                    com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.LONG_CLICK -> {
                        val pt = action.points.firstOrNull() ?: continue
                        val path = android.graphics.Path().apply { moveTo(pt.x, pt.y); lineTo(pt.x+1, pt.y+1) }
                        VisionActionExecutor.dispatchPath(path, if (action.duration < 100) 500 else action.duration)
                    }
                    com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.SWIPE -> {
                        val start = action.points.firstOrNull() ?: continue
                        val end = action.points.lastOrNull() ?: continue
                        val path = android.graphics.Path().apply { moveTo(start.x, start.y); lineTo(end.x, end.y) }
                        VisionActionExecutor.dispatchPath(path, action.duration)
                    }
                    com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.WAIT -> {
                        kotlinx.coroutines.delay(action.duration)
                        true
                    }
                }
                
                if (!success && action.type != com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType.WAIT) {
                    Log.w(TAG, "Failed executing action: $action")
                    return NodeResult.Failure("Failed to dispatch gesture action")
                }
                
                val waitTime = if (action.delayAfter < 100) 500 else action.delayAfter
                kotlinx.coroutines.delay(waitTime)
            }
            return NodeResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing back recorded actions", e)
            return NodeResult.Failure("Malformed recorded gesture actions: ${e.message}")
        }
    }
}
