package com.autonion.automationcompanion.features.screen_understanding_ml.logic

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.autonion.automationcompanion.AccessibilityFeature
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.automation_debugger.DebugLogger
import com.autonion.automationcompanion.features.automation_debugger.data.LogCategory
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionIntent
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        if (!dispatchClick(s, point)) return false
        delay(500) // Wait for focus
        
        // 2. Try to find the node and set text directly
        // This is a best-effort approach since we only have coordinates. 
        // We really rely on the service to have found the element. 
        // But here we can try to find the focus.
        val root = s.rootInActiveWindow
        val focused = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        
        if (focused != null) {
             val clearArgs = android.os.Bundle()
             clearArgs.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
             focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
             
             var success = false
             val clipboard = s.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
             if (clipboard != null) {
                 val clip = android.content.ClipData.newPlainText("automation", text)
                 clipboard.setPrimaryClip(clip)
                 success = focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
             }
             
             if (!success) {
                 val args = android.os.Bundle()
                 args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                 success = focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
             }

             Log.d("ActionExecutor", "Set text on focused node: $success")
             if (success) {
                 DebugLogger.success(
                     s.baseContext, LogCategory.UI_RECOGNITION_AI,
                     "Text input success",
                     "Set text '$text' on focused input node",
                     "ActionExecutor"
                 )
             } else {
                 DebugLogger.warning(
                     s.baseContext, LogCategory.UI_RECOGNITION_AI,
                     "Text input failed",
                     "performAction(SET_TEXT) returned false for '$text'",
                     "ActionExecutor"
                 )
             }
             return success
        } else {
             Log.w("ActionExecutor", "Could not find focused input node to set text")
             DebugLogger.warning(
                 s.baseContext, LogCategory.UI_RECOGNITION_AI,
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

        return dispatchGesture(service, gesture)
    }
    
    @Suppress("DEPRECATION")
    private suspend fun dispatchScroll(service: AccessibilityService, point: PointF, direction: String): Boolean =
        withContext(Dispatchers.Default) {
            if (direction != "up" && direction != "down") return@withContext false
            val metrics = DisplayMetrics()
            service.getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(metrics)
            val display = RectF(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
            val root = service.rootInActiveWindow
            try {
                ScrollAction.execute(root, display, point, direction == "down") { swipe ->
                    val path = Path().apply {
                        moveTo(swipe.x, swipe.fromY)
                        lineTo(swipe.x, swipe.toY)
                    }
                    val stroke = GestureDescription.StrokeDescription(path, 0, 500)
                    dispatchGesture(service, GestureDescription.Builder().addStroke(stroke).build())
                }
            } finally { root?.recycle() }
        }

    private suspend fun dispatchGesture(service: AccessibilityService, gesture: GestureDescription): Boolean =
        withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(3000L) {
                suspendCancellableCoroutine { continuation ->
                    val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }, null)
                    // Rejected dispatches do not receive a completion callback.
                    if (!accepted && continuation.isActive) continuation.resume(false)
                }
            } ?: false
        }

}
