package com.autonion.automationcompanion.features.visual_trigger.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import com.autonion.automationcompanion.AccessibilityFeature
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.visual_trigger.models.VisionAction
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object VisionActionExecutor : AccessibilityFeature {

    private var serviceRef: java.lang.ref.WeakReference<AccessibilityService>? = null

    init {
        AccessibilityRouter.register(this)
    }

    override fun onServiceConnected(service: AccessibilityService) {
        serviceRef = java.lang.ref.WeakReference(service)
        Log.d("VisionActionExecutor", "Connected to AccessibilityService")
    }

    override fun onServiceDisconnected() {
        serviceRef = null
        Log.d("VisionActionExecutor", "Disconnected from AccessibilityService")
    }
    
    fun isConnected(): Boolean = serviceRef?.get() != null

    suspend fun execute(action: VisionAction, point: PointF): Boolean {
        val service = serviceRef?.get() ?: return false
        
        return when (action) {
            is VisionAction.Click -> dispatchClick(service, point)
            is VisionAction.LongClick -> dispatchLongClick(service, point)
            is VisionAction.Scroll -> dispatchScroll(service, point, action.direction)
        }
    }

    suspend fun dispatchPath(path: Path, duration: Long): Boolean {
        val service = serviceRef?.get() ?: return false
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(service, gesture)
    }

    private suspend fun dispatchClick(service: AccessibilityService, point: PointF): Boolean {
        val path = Path().apply {
            moveTo(point.x, point.y)
            lineTo(point.x, point.y) 
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return dispatchGesture(service, gesture)
    }
    
    private suspend fun dispatchLongClick(service: AccessibilityService, point: PointF): Boolean {
        val path = Path().apply {
            moveTo(point.x, point.y)
            lineTo(point.x, point.y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 1000)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return dispatchGesture(service, gesture)
    }
    
    private suspend fun dispatchScroll(service: AccessibilityService, point: PointF, direction: com.autonion.automationcompanion.features.visual_trigger.models.ScrollDirection): Boolean {
        val path = Path()
        val startX = point.x
        val startY = point.y
        val displayHeight = service.resources.displayMetrics.heightPixels.toFloat()
        val distance = displayHeight * 0.5f

        // "Scroll Down" means reveal bottom -> Swipe UP
        // "Scroll Up" means reveal top -> Swipe DOWN
        // Assuming ScrollDirection enum matches this
        
        when (direction) {
            com.autonion.automationcompanion.features.visual_trigger.models.ScrollDirection.UP -> {
                 // Scroll Up -> Content moves down -> Swipe Down
                 path.moveTo(startX, startY - distance/2)
                 path.lineTo(startX, startY + distance/2)
            }
            com.autonion.automationcompanion.features.visual_trigger.models.ScrollDirection.DOWN -> {
                 // Scroll Down -> Content moves up -> Swipe Up
                 path.moveTo(startX, startY + distance/2)
                 path.lineTo(startX, startY - distance/2)
            }
            // Left/Right omitted for brevity but similar logic
            else -> return false
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 500)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return dispatchGesture(service, gesture)
    }

    private suspend fun dispatchGesture(service: AccessibilityService, gesture: GestureDescription): Boolean {
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
