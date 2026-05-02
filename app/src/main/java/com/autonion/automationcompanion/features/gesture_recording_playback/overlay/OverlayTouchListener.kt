package com.autonion.automationcompanion.features.gesture_recording_playback.overlay

import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class OverlayTouchListener(
    private val windowManager: WindowManager,
    private val view: View,
    private val params: WindowManager.LayoutParams
) : View.OnTouchListener {

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        // If this listener is called, it means the touch wasn't consumed by any child view (like a button).
        // So we can safely assume this is a touch on the panel background and handle dragging.

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true // Consume the event to start tracking the drag
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                try {
                    windowManager.updateViewLayout(view, params)
                } catch (_: Exception) {
                    // Ignore errors if view is removed
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                // Drag finished
                return true
            }
        }
        return false
    }
}