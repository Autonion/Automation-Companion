package com.autonion.automationcompanion.features.gesture_recording_playback.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.R

class ActionMarker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var text: String = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(10f, 0f, 4f, Color.parseColor("#40000000"))
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f // Slightly larger text
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#80000000"))
    }
    private var circleRadius = 60f // Increased size for better text fit
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var originalElevation = 0f

    // Configs
    private val TOUCH_PADDING = 8f
    private val DRAG_ELEVATION = 200f
    var isDraggable = true
    var isVisible = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
        }

    private var positionChangedListener: ((Float, Float) -> Unit)? = null
    // Dynamic colors based on action type
    private val TYPE_CLICK_COLOR = Color.parseColor("#4CAF50") // Green
    private val TYPE_SWIPE_COLOR = Color.parseColor("#2196F3") // Blue
    private val TYPE_LONG_PREFIX_COLOR = Color.parseColor("#FF5722") // Deep Orange for Long Press
    
    // Restore missing variable (default to Blue)
    private var originalColor: Int = TYPE_SWIPE_COLOR

    init {
        paint.color = originalColor
        setLayerType(LAYER_TYPE_SOFTWARE, paint)
    }

    fun setText(text: String) {
        var processed = text
        
        // 1. Determine Color & clean text
        when {
            processed.contains("SWIPE", ignoreCase = true) -> {
                originalColor = TYPE_SWIPE_COLOR
                processed = processed.replace("SWIPE ", "", ignoreCase = true)
            }
            processed.contains("LONG_CLICK", ignoreCase = true) -> {
                originalColor = TYPE_LONG_PREFIX_COLOR
                processed = processed.replace("LONG_CLICK", "Hold", ignoreCase = true)
            }
            processed.contains("CLICK", ignoreCase = true) -> {
                originalColor = TYPE_CLICK_COLOR
                // Keep "Click" word
            }
        }
        
        this.text = processed.trim()
        paint.color = originalColor
        invalidate()
    }

    fun setOnPositionChanged(listener: ((Float, Float) -> Unit)?) {
        positionChangedListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Add padding for shadow and border
        val desiredSize = (circleRadius * 2 + 24).toInt()
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isVisible) return

        val cx = width / 2f
        val cy = height / 2f

        // 1. Draw main colored circle with shadow
        paint.style = Paint.Style.FILL
        paint.color = originalColor
        canvas.drawCircle(cx, cy, circleRadius, paint)

        // 2. Draw border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = Color.WHITE
        paint.clearShadowLayer()
        canvas.drawCircle(cx, cy, circleRadius, paint)
        paint.setShadowLayer(10f, 0f, 4f, Color.parseColor("#40000000"))

        // 3. Draw text (Multi-line support)
        val lines = text.split(" ")
        if (lines.size > 1) {
            val totalHeight = lines.size * textPaint.textSize
            var startY = cy - (totalHeight / 2) + textPaint.textSize / 2 // Approximate centering
            
            // Adjust spacing slightly
            val lineHeight = textPaint.textSize * 1.1f
            startY = cy - (lines.size * lineHeight) / 2 + lineHeight * 0.75f

            lines.forEachIndexed { index, line ->
                canvas.drawText(line, cx, startY + (index * lineHeight), textPaint)
            }
        } else {
            // Single line centering
            val textHeight = textPaint.descent() - textPaint.ascent()
            val textOffset = (textHeight / 2) - textPaint.descent()
            canvas.drawText(text, cx, cy + textOffset, textPaint)
        }
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