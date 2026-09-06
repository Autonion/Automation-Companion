package com.autonion.automationcompanion.features.gesture_recording_playback.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ConnectionLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f

    // Dark outline stroke — drawn first, thicker, for contrast on light backgrounds
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC333333")
        strokeWidth = 9f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    // Bright foreground stroke — drawn on top for visibility on dark backgrounds
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Cyan — visible on both white and dark
        strokeWidth = 4f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    fun updatePosition(startX: Float, startY: Float, endX: Float, endY: Float) {
        this.startX = startX
        this.startY = startY
        this.endX = endX
        this.endY = endY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Draw dark outline first for contrast on white/light backgrounds
        canvas.drawLine(startX, startY, endX, endY, outlinePaint)
        // Then draw the bright line on top
        canvas.drawLine(startX, startY, endX, endY, linePaint)
    }
}