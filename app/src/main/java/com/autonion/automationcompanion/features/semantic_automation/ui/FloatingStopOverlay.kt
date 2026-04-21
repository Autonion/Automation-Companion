package com.autonion.automationcompanion.features.semantic_automation.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.graphics.PixelFormat
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationService

/**
 * A floating "Stop" button overlay that appears over all apps while
 * semantic automation is running.
 *
 * Features:
 * - Small circular button (48dp) with a stop icon
 * - Draggable to any position on screen
 * - Semi-transparent when idle, fully opaque on touch
 * - Tapping sends ACTION_STOP to [SemanticAutomationService]
 * - Uses TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW permission)
 */
class FloatingStopOverlay(private val context: Context) {

    companion object {
        private const val TAG = "FloatingStopOverlay"
        private const val BUTTON_SIZE_DP = 48
        private const val ICON_SIZE_DP = 24
        private const val IDLE_ALPHA = 0.55f
        private const val ACTIVE_ALPHA = 1.0f
        private const val CLICK_THRESHOLD_PX = 10
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isShowing = false

    /**
     * Show the floating stop button overlay.
     * No-op if already showing or if overlay permission is not granted.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val density = context.resources.displayMetrics.density
            val buttonSizePx = (BUTTON_SIZE_DP * density).toInt()
            val iconSizePx = (ICON_SIZE_DP * density).toInt()
            val paddingPx = (12 * density).toInt()

            // ── Build the stop button view ──
            val container = FrameLayout(context).apply {
                // Red circle background
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#E53935")) // Material Red 600
                    setStroke((2 * density).toInt(), Color.parseColor("#C62828")) // darker border
                }
                elevation = 8 * density
                alpha = IDLE_ALPHA
            }

            val stopIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_notification) // Use existing icon as fallback
                // Draw a white square "stop" shape
                setImageDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 2 * density
                    setColor(Color.WHITE)
                    setSize(iconSizePx, iconSizePx)
                })
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = "Stop Automation"
            }

            val iconLayoutParams = FrameLayout.LayoutParams(iconSizePx, iconSizePx).apply {
                gravity = Gravity.CENTER
            }
            container.addView(stopIcon, iconLayoutParams)

            // ── Window layout params ──
            val layoutParams = WindowManager.LayoutParams(
                buttonSizePx,
                buttonSizePx,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Default position: bottom-right area
                val displayMetrics = context.resources.displayMetrics
                x = displayMetrics.widthPixels - buttonSizePx - paddingPx
                y = (displayMetrics.heightPixels * 0.7).toInt()
            }

            // ── Touch handling: drag + tap ──
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false

            container.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        v.alpha = ACTIVE_ALPHA
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (!isDragging && (Math.abs(dx) > CLICK_THRESHOLD_PX || Math.abs(dy) > CLICK_THRESHOLD_PX)) {
                            isDragging = true
                        }

                        if (isDragging) {
                            layoutParams.x = initialX + dx
                            layoutParams.y = initialY + dy
                            try {
                                windowManager?.updateViewLayout(v, layoutParams)
                            } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.alpha = IDLE_ALPHA
                        if (!isDragging) {
                            // It's a tap → stop automation
                            Log.d(TAG, "Stop button tapped — sending ACTION_STOP")
                            stopAutomation()
                        }
                        true
                    }
                    else -> false
                }
            }

            // ── Add to WindowManager ──
            windowManager?.addView(container, layoutParams)
            overlayView = container
            isShowing = true
            Log.d(TAG, "Floating stop overlay shown")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show floating stop overlay", e)
            isShowing = false
        }
    }

    /**
     * Remove the floating stop button overlay.
     */
    fun hide() {
        if (!isShowing) return
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove floating stop overlay", e)
        } finally {
            overlayView = null
            isShowing = false
            Log.d(TAG, "Floating stop overlay hidden")
        }
    }

    /**
     * Send ACTION_STOP to the SemanticAutomationService.
     */
    private fun stopAutomation() {
        try {
            val stopIntent = Intent(context, SemanticAutomationService::class.java).apply {
                action = SemanticAutomationService.ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(stopIntent)
            } else {
                context.startService(stopIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stop intent", e)
        }
    }
}
