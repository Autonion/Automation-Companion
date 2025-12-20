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

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f) // Dashed line
    }

    fun updatePosition(startX: Float, startY: Float, endX: Float, endY: Float) {
        this.startX = startX
        this.startY = startY
        this.endX = endX
        this.endY = endY
        invalidate()
    }
    
    // Helper to calculate positions relative to this view (which should be MATCH_PARENT in setup)
    // Actually, markers send SCREEN coordinates.
    // If this view is MATCH_PARENT in the overlay window (at 0,0), then view coords == screen coords.
    // So we can use raw values directly.

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawLine(startX, startY, endX, endY, linePaint)
    }
}