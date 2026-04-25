package com.autonion.automationcompanion.core.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import com.autonion.automationcompanion.R
import kotlin.math.abs

/**
 * Shared overlay styling constants and factory methods.
 *
 * All system overlays (Gesture Recording, Screen ML, Visual Trigger, Semantic Automation)
 * should use these constants for a unified visual language.
 *
 * NOTE: This does NOT replace the Gesture Recording's XML layout.
 * It provides shared constants and programmatic builders for the other overlays.
 */
object OverlayStyles {

    // ── Colors ──────────────────────────────────────────────────────────────────
    /** Primary overlay panel background — deep navy with high alpha */
    const val PANEL_BG_COLOR = 0xE61A1A2E.toInt()

    /** Panel border/stroke color — subtle white */
    const val PANEL_STROKE_COLOR = 0x40FFFFFF

    /** Button ripple/pressed state */
    const val BUTTON_PRESSED_COLOR = 0x33FFFFFF

    /** Accent green for primary actions (Capture, Save, Confirm) */
    const val ACCENT_GREEN = 0xFF00C853.toInt()

    /** Accent red for destructive/stop actions */
    const val ACCENT_RED = 0xFFE53935.toInt()

    /** Cancel/close icon tint */
    const val ICON_TINT_NORMAL = 0xDDFFFFFF.toInt()

    /** Text color on overlay */
    const val TEXT_COLOR = 0xFFFFFFFF.toInt()

    // ── Dimensions (in dp) ──────────────────────────────────────────────────────
    const val PANEL_CORNER_RADIUS_DP = 24f
    const val PANEL_STROKE_WIDTH_DP = 1.5f
    const val PANEL_PADDING_H_DP = 16
    const val PANEL_PADDING_V_DP = 10
    const val PANEL_ELEVATION_DP = 8f

    const val ICON_SIZE_DP = 22
    const val BUTTON_PADDING_DP = 10
    const val BUTTON_CORNER_RADIUS_DP = 20f
    const val BUTTON_SPACING_DP = 10

    const val CLOSE_BUTTON_SIZE_DP = 36

    /** Drag threshold in pixels before treating a touch as a drag vs click */
    const val DRAG_THRESHOLD_PX = 8

    // ── Panel Background ────────────────────────────────────────────────────────

    /**
     * Creates the standard overlay panel background — dark pill shape with a subtle border.
     */
    fun createPanelBackground(density: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = PANEL_CORNER_RADIUS_DP * density
            setColor(PANEL_BG_COLOR)
            setStroke((PANEL_STROKE_WIDTH_DP * density).toInt(), PANEL_STROKE_COLOR)
        }
    }

    /**
     * Creates a standard pill button background with the given color.
     */
    fun createButtonBackground(density: Float, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = BUTTON_CORNER_RADIUS_DP * density
            setColor(color)
        }
    }

    /**
     * Creates a circular button background (for close/cancel buttons).
     */
    fun createCircleBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    // ── Icon Buttons ────────────────────────────────────────────────────────────

    /**
     * Creates a standard overlay icon button with consistent sizing and tint.
     *
     * @param context The context for creating the view
     * @param iconRes Drawable resource for the icon
     * @param contentDescription Accessibility description
     * @param tint Icon color tint (defaults to white)
     * @param onClick Click handler
     */
    fun createIconButton(
        context: Context,
        iconRes: Int,
        contentDescription: String,
        tint: Int = ICON_TINT_NORMAL,
        onClick: () -> Unit
    ): ImageView {
        val dp = context.resources.displayMetrics.density
        val sizePx = (CLOSE_BUTTON_SIZE_DP * dp).toInt()
        val paddingPx = (BUTTON_PADDING_DP * dp).toInt()

        return ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            isClickable = true
            isFocusable = true
            background = createCircleBackground(BUTTON_PRESSED_COLOR)
            this.contentDescription = contentDescription
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setOnClickListener { onClick() }
        }
    }

    /**
     * Creates an accent pill button (e.g., green "Capture" button with icon + label).
     *
     * @param context The context
     * @param iconRes Icon drawable resource
     * @param label Button text
     * @param bgColor Button background color
     * @param onClick Click handler
     */
    fun createAccentButton(
        context: Context,
        iconRes: Int,
        label: String,
        bgColor: Int = ACCENT_GREEN,
        onClick: () -> Unit
    ): LinearLayout {
        val dp = context.resources.displayMetrics.density

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (14 * dp).toInt(), (8 * dp).toInt(),
                (14 * dp).toInt(), (8 * dp).toInt()
            )
            background = createButtonBackground(dp, bgColor)
            isClickable = true
            isFocusable = true

            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(TEXT_COLOR)
            }
            icon.layoutParams = LinearLayout.LayoutParams(
                (ICON_SIZE_DP * dp).toInt(),
                (ICON_SIZE_DP * dp).toInt()
            )
            addView(icon)

            val text = android.widget.TextView(context).apply {
                this.text = label
                setTextColor(TEXT_COLOR)
                textSize = 13f
                setPadding((6 * dp).toInt(), 0, 0, 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            addView(text)

            setOnClickListener { onClick() }
        }
    }

    // ── WindowManager Layout Params ─────────────────────────────────────────────

    /**
     * Creates standard overlay WindowManager.LayoutParams for a floating control panel.
     */
    fun createOverlayLayoutParams(
        context: Context,
        gravity: Int = Gravity.TOP or Gravity.START,
        x: Int? = null,
        y: Int? = null
    ): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x ?: (metrics.widthPixels - (200 * metrics.density).toInt())
            this.y = y ?: (metrics.heightPixels / 3)
        }
    }

    // ── Drag Helper ─────────────────────────────────────────────────────────────

    /**
     * Attaches standard drag-to-move behavior to an overlay view.
     * Distinguishes drags from taps using [DRAG_THRESHOLD_PX].
     *
     * @param view The view to make draggable
     * @param layoutParams The WindowManager.LayoutParams to update during drag
     * @param windowManager The WindowManager instance
     */
    fun attachDragBehavior(
        view: View,
        layoutParams: WindowManager.LayoutParams,
        windowManager: WindowManager
    ) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX) isDragging = true
                    if (isDragging) {
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(view, layoutParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> isDragging
                else -> false
            }
        }
    }

    // ── Permission Check ────────────────────────────────────────────────────────

    /**
     * Checks if the overlay (draw over other apps) permission is granted.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
}
