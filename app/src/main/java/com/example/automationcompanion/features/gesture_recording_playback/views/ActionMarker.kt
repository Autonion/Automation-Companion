package com.example.automationcompanion.features.gesture_recording_playback.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.example.automationcompanion.R

class ActionMarker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var text: String = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private var circleRadius = 60f // Increased radius for easier selection
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var originalElevation = 0f

    // Configs
    private val TOUCH_PADDING = 8f    // reduce effective touch area slightly (pixels)
    private val DRAG_ELEVATION = 200f // elevation while dragging to be top-most
    var isDraggable = true
    var isVisible = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
        }

    private var positionChangedListener: ((Float, Float) -> Unit)? = null
    private var originalColor: Int = try {
        ContextCompat.getColor(context, R.color.marker_color)
    } catch (_: Exception) {
        Color.RED
    }

    init {
        paint.color = originalColor
    }

    fun setText(text: String) {
        this.text = text
        invalidate()
    }

    fun setOnPositionChanged(listener: ((Float, Float) -> Unit)?) {
        positionChangedListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = (circleRadius * 2 + 10).toInt()
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isVisible) return

        val cx = width / 2f
        val cy = height / 2f

        // 1. Draw main colored circle
        paint.color = originalColor
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, circleRadius, paint)

        // 2. Draw text
        canvas.drawText(text, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)

        // 3. Draw border (and restore paint state)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, circleRadius, paint)

        // **Crucial Fix**: Restore paint to its original state for the next draw call
        paint.style = Paint.Style.FILL
        paint.color = originalColor
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDraggable) return false

        // Use local coords to do a precise circular hit-test (center-based)
        val localX = event.x
        val localY = event.y
        val cx = width / 2f
        val cy = height / 2f
        val dx = localX - cx
        val dy = localY - cy
        val distanceSq = dx * dx + dy * dy
        val effectiveRadius = (circleRadius - TOUCH_PADDING).coerceAtLeast(8f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only claim the touch if it lies within the marker's circular hit area.
                if (distanceSq > effectiveRadius * effectiveRadius) {
                    // Not a hit for this marker; allow other views to receive it.
                    return false
                }

                lastTouchX = event.rawX
                lastTouchY = event.rawY

                // Bring this marker to front so it receives subsequent events when near others
                originalElevation = elevation
                // If API supports elevation, raise it so it's top-most
                elevation = DRAG_ELEVATION
                // Also call bringToFront and request layout/invalidate on parent to update drawing order
                (parent as? ViewGroup)?.let { parentGroup ->
                    bringToFront()
                    parentGroup.requestLayout()
                    parentGroup.invalidate()
                }

                // Prevent parent from intercepting while we're dragging this marker
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)

                // mark pressed for visual feedback if needed
                isPressed = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - lastTouchX
                val deltaY = event.rawY - lastTouchY

                x += deltaX
                y += deltaY

                lastTouchX = event.rawX
                lastTouchY = event.rawY

                // Report LOCAL center coordinates relative to parent (since x/y are relative to parent)
                val localCenterX = x + width / 2f
                val localCenterY = y + height / 2f

                positionChangedListener?.invoke(localCenterX, localCenterY)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // restore elevation / pressed state
                elevation = originalElevation
                isPressed = false

                // allow parent to intercept future touches
                (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)

                // Treat up as a click if it wasn't a big drag.
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun removeFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }
}