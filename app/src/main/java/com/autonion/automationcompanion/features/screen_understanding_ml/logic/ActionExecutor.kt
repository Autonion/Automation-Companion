package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.AccessibilityFeature
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object ActionExecutor : AccessibilityFeature {

    private var serviceRef: java.lang.ref.WeakReference<AccessibilityService>? = null

    init {
        AccessibilityRouter.register(this)
    }

    override fun onServiceConnected(service: AccessibilityService) {
        this.serviceRef = java.lang.ref.WeakReference(service)
        Log.d("ActionExecutor", "Connected to AccessibilityService")
    }

    override fun onServiceDisconnected() {
        this.serviceRef = null
        Log.d("ActionExecutor", "Disconnected from AccessibilityService")
    }

    suspend fun execute(context: android.content.Context, action: ActionIntent): Boolean {
        val s = serviceRef?.get()
        if (s == null) {
            Log.e("ActionExecutor", "AccessibilityService not connected")
            DebugLogger.error(
                context, 
                LogCategory.SYSTEM_CONTEXT,
                "Accessibility Service permission required skipping",
                "Action ${action.type} failed - Service not connected",
                "ActionExecutor"
            )
            return false
        }

        return when (action.type) {
            ActionType.CLICK -> {
                if (action.targetPoint != null) {
                    dispatchClick(s, action.targetPoint)
                } else false
            }
            ActionType.SCROLL_UP -> {
                 if (action.targetPoint != null) executeScroll(action.targetPoint, "up") else false
            }
            ActionType.SCROLL_DOWN -> {
                 if (action.targetPoint != null) executeScroll(action.targetPoint, "down") else false
            }
            ActionType.INPUT_TEXT -> {
                 if (action.targetPoint != null && action.inputText != null) {
                     executeInputText(action.targetPoint, action.inputText)
                 } else false
            }
            ActionType.WAIT -> {
                delay(2000)
                true
            }
            ActionType.FINISH -> true
            else -> false
        }
    }

    suspend fun executeClick(point: PointF): Boolean {
        val s = serviceRef?.get() ?: return false
        return dispatchClick(s, point)
    }

    suspend fun executeScroll(point: PointF, direction: String): Boolean {
        val s = serviceRef?.get() ?: return false
        return dispatchScroll(s, point, direction)
    }
    
    suspend fun executeInputText(point: PointF, text: String): Boolean {
        val s = serviceRef?.get() ?: return false
        
        // 1. First click to focus
        dispatchClick(s, point)
        delay(500) // Wait for focus
        
        // 2. Try to find the node and set text directly
        // This is a best-effort approach since we only have coordinates. 
        // We really rely on the service to have found the element. 
        // But here we can try to find the focus.
        val root = s.rootInActiveWindow
        val focused = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        
        if (focused != null) {
             val args = android.os.Bundle()
             args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
             val success = focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
             Log.d("ActionExecutor", "Set text on focused node: $success")
             if (success) {
                 DebugLogger.success(
                     s.baseContext, LogCategory.SCREEN_CONTEXT_AI,
                     "Text input success",
                     "Set text '$text' on focused input node",
                     "ActionExecutor"
                 )
             } else {
                 DebugLogger.warning(
                     s.baseContext, LogCategory.SCREEN_CONTEXT_AI,
                     "Text input failed",
                     "performAction(SET_TEXT) returned false for '$text'",
                     "ActionExecutor"
                 )
             }
             return success
        } else {
             Log.w("ActionExecutor", "Could not find focused input node to set text")
             DebugLogger.warning(
                 s.baseContext, LogCategory.SCREEN_CONTEXT_AI,
                 "No focused input node",
                 "Cannot set text — no focused input node found after click",
                 "ActionExecutor"
             )
             return false
        }
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
    
    private suspend fun dispatchScroll(service: AccessibilityService, point: PointF, direction: String): Boolean {
        val path = Path()
        val startX = point.x
        val startY = point.y
        // Scroll Down content = Swipe Up gesture
        // Scroll Up content = Swipe Down gesture
        // Wait, "Scroll Down" usually means "Move content down" (Swipe down) or "Go to bottom" (Swipe up)?
        // User said "Scroll (up/down)". usually "Scroll Down" means "I want to see what is below", so I swipe UP.
        
        val displayHeight = service.resources.displayMetrics.heightPixels
        val distance = displayHeight * 0.5f // Swipe 50% of screen
        
        if (direction == "down") {
            // Visualize: Content moves UP, we see bottom. Gesture: Swipe UP from bottom to top? 
            // Standard convention: "Scroll Down" -> List goes UP. Finger moves UP? No, finger moves UP drags content UP.
            // Wait. Finger moves UP -> Content moves UP -> We see what's BELOW.
            // So for "Scroll Down" (reveal bottom), we swipe UP.
            path.moveTo(startX, startY + 200) 
            path.lineTo(startX, startY - distance)
        } else {
            // "Scroll Up" -> Reveal top. Content moves DOWN. Finger moves DOWN.
            path.moveTo(startX, startY - 200)
            path.lineTo(startX, startY + distance)
        }
        
        // Clamp to screen? dispatchGesture clips automatically usually, but safer to start from safe area.
        // Actually, let's just use the element center as anchor.
        
        val stroke = GestureDescription.StrokeDescription(path, 0, 500)
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
