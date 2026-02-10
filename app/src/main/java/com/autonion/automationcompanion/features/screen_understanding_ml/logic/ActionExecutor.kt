package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.AccessibilityFeature
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object ActionExecutor : AccessibilityFeature {

    private var service: AccessibilityService? = null

    init {
        AccessibilityRouter.register(this)
    }

    override fun onServiceConnected(service: AccessibilityService) {
        this.service = service
        Log.d("ActionExecutor", "Connected to AccessibilityService")
    }

    suspend fun execute(action: ActionIntent): Boolean {
        val s = service
        if (s == null) {
            Log.e("ActionExecutor", "AccessibilityService not connected")
            return false
        }

        return when (action.type) {
            ActionType.CLICK -> {
                if (action.targetPoint != null) {
                    dispatchClick(s, action.targetPoint)
                } else false
            }
            ActionType.WAIT -> {
                delay(1000)
                true
            }
            ActionType.FINISH -> true
            // Implement other types
            else -> false
        }
    }

    suspend fun executeClick(point: PointF): Boolean {
        val s = service
        if (s == null) {
            Log.e("ActionExecutor", "AccessibilityService not connected")
            return false
        }
        return dispatchClick(s, point)
    }

    private suspend fun dispatchClick(service: AccessibilityService, point: PointF): Boolean {
        Log.d("ActionExecutor", "Dispatching click at $point")
        val path = Path().apply {
            moveTo(point.x, point.y)
            lineTo(point.x + 1, point.y + 1)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCoroutine { continuation ->
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    continuation.resume(false)
                }
            }, null)
        }
    }
}
