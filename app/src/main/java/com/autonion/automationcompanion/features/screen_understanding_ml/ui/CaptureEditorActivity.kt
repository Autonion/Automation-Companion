package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.autonion.automationcompanion.features.screen_understanding_ml.core.PerceptionLayer
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class CaptureEditorActivity : ComponentActivity() {

    private var sourceBitmap: Bitmap? = null
    private var editorView: EditorView? = null
    private var perceptionLayer: PerceptionLayer? = null
    private var presetName: String = "Untitled"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val imagePath = intent.getStringExtra("IMAGE_PATH")
        presetName = intent.getStringExtra("PRESET_NAME") ?: "Untitled"
        
        if (imagePath == null) {
            Toast.makeText(this, "No image provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        val file = File(imagePath)
        if (!file.exists()) {
            Toast.makeText(this, "Image file not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        sourceBitmap = BitmapFactory.decodeFile(file.absolutePath)
        perceptionLayer = PerceptionLayer(this)
        
        setupUI()
        runDetection()
    }
    
    private fun setupUI() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        
        editorView = EditorView(this, sourceBitmap!!)
        root.addView(editorView)
        
        // Control Panel
        val panel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(16, 16, 16, 16)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
        }
        
        val btnCancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }

        val btnRetake = Button(this).apply {
            text = "🔄 Retake"
            setOnClickListener {
                Toast.makeText(this@CaptureEditorActivity, "Tap Snap again to retake", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
                finish()
            }
        }
        
        val btnAdd = Button(this).apply {
            text = "✓ Add to Preset"
            setOnClickListener { addToPreset() }
        }
        
        val spacer1 = View(this).apply {
             layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
        }
        val spacer2 = View(this).apply {
             layoutParams = android.widget.LinearLayout.LayoutParams(0, 1, 1f)
        }
        
        panel.addView(btnCancel)
        panel.addView(spacer1)
        panel.addView(btnRetake)
        panel.addView(spacer2)
        panel.addView(btnAdd)
        
        root.addView(panel)
        setContentView(root)
    }
    
    private fun runDetection() {
        Toast.makeText(this, "Detecting UI elements...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.Default) {
             val elements = perceptionLayer?.detect(sourceBitmap!!) ?: emptyList()
             withContext(Dispatchers.Main) {
                 if (elements.isEmpty()) {
                     Toast.makeText(this@CaptureEditorActivity, "No elements found — try Retake", Toast.LENGTH_SHORT).show()
                 } else {
                     Toast.makeText(this@CaptureEditorActivity, "Found ${elements.size} elements", Toast.LENGTH_SHORT).show()
                     editorView?.setElements(elements)
                 }
             }
        }
    }
    
    private fun addToPreset() {
        // We use ScreenAgentOverlay's methods via accessing the overlay instance indirectly?
        // Wait, 'editorView' in CaptureEditorActivity is NOT ScreenAgentOverlay instance. 
        // It is 'com.autonion.automationcompanion.features.screen_understanding_ml.ui.ScreenAgentOverlay.OverlayView'?
        // No, let's look at Step 1545. 'editorView?.getSelectedElements()'.
        // If CaptureEditorActivity uses the same OverlayView logic, it must have access to these new methods.
        // Assuming 'editorView' is the view.
        
        val selected = editorView?.getSelectedElements() ?: emptyList()
        val configs = editorView?.getSelectionConfig() ?: emptyList() 
        val actionTypes = editorView?.getSelectionActionTypes() ?: emptyList()
        val inputTexts = editorView?.getSelectionInputTexts() ?: emptyList()
        
        if (selected.isEmpty()) {
            Toast.makeText(this, "Please select at least one element", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Safety check for list sizes
        val count = selected.size
        // Ensure other lists are same size (they should be if logic is correct)
        
        val steps = (0 until count).map { i ->
             AutomationStep(
                 id = UUID.randomUUID().toString(),
                 orderIndex = i,
                 label = selected[i].label,
                 anchor = selected[i],
                 isOptional = configs.getOrElse(i) { false },
                 actionType = actionTypes.getOrElse(i) { com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType.CLICK }, // FQN if Import missing
                 inputText = inputTexts.getOrElse(i) { null }
             )
        }
        
        // Send steps back to the running service
        val service = com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService.instance
        if (service != null) {
            service.addStepsFromEditor(steps)
            moveTaskToBack(true)
            finish()
        } else {
            Toast.makeText(this, "Error: Service not running! Elements lost.", Toast.LENGTH_LONG).show()
            android.util.Log.e("CaptureEditor", "Service instance is null")
        }
    }
    
    override fun onResume() {
        super.onResume()
        com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService.instance?.setOverlayVisibility(false)
    }

    override fun onPause() {
        super.onPause()
        com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService.instance?.setOverlayVisibility(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        perceptionLayer?.close()
    }

    data class SelectionState(
        val element: UIElement, 
        var isOptional: Boolean = false,
        var actionType: ActionType = ActionType.CLICK,
        var inputText: String? = null
    )

    // Inner class for simple editing view
    private inner class EditorView(context: android.content.Context, val bitmap: Bitmap) : View(context) {
        
        private val paintBox = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val paintSelected = Paint().apply {
            color = Color.BLUE 
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val paintBadge = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.FILL
        }
        private val paintBadgeText = Paint().apply {
            color = Color.WHITE
            textSize = 30f
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val paintDashed = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 8f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 20f), 0f)
        }
        
        private var elements: List<UIElement> = emptyList()
        private val selectionStates: MutableList<SelectionState> = mutableListOf()
        
        // Scale handling
        private var scaleFactor = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        
        fun setElements(newElements: List<UIElement>) {
            elements = newElements
            invalidate()
        }
        
        fun getSelectedElements() = selectionStates.map { it.element }
        fun getSelectionConfig() = selectionStates.map { it.isOptional }
        fun getSelectionActionTypes() = selectionStates.map { it.actionType }
        fun getSelectionInputTexts() = selectionStates.map { it.inputText }

        private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val touchX = (e.x - offsetX) / scaleFactor
                val touchY = (e.y - offsetY) / scaleFactor
                
                val clicked = elements.find { it.bounds.contains(touchX, touchY) }
                if (clicked != null) {
                    toggleSelection(clicked)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val touchX = (e.x - offsetX) / scaleFactor
                val touchY = (e.y - offsetY) / scaleFactor
                
                val clicked = elements.find { it.bounds.contains(touchX, touchY) }
                if (clicked != null) {
                    val state = selectionStates.find { it.element.id == clicked.id }
                    if (state != null) {
                        showActionPicker(state)
                    }
                }
            }
        })

        override fun onTouchEvent(event: MotionEvent): Boolean {
            return gestureDetector.onTouchEvent(event)
        }

        private fun showActionPicker(state: SelectionState) {
            val element = state.element
            val isInput = element.label.contains("Input", ignoreCase = true) || 
                          element.label.contains("Edit", ignoreCase = true)
            
            val options = mutableListOf("Click (Default)", "Scroll Up", "Scroll Down", "Wait")
            val types = mutableListOf(ActionType.CLICK, ActionType.SCROLL_UP, ActionType.SCROLL_DOWN, ActionType.WAIT)
            
            if (isInput) {
                options.add("Input Text")
                types.add(ActionType.INPUT_TEXT)
            }
            
            AlertDialog.Builder(this@CaptureEditorActivity)
                .setTitle("Choose Action for ${element.label}")
                .setItems(options.toTypedArray()) { _, which ->
                    val selectedType = types[which]
                    if (selectedType == ActionType.INPUT_TEXT) {
                        showInputTextDialog(state)
                    } else {
                        state.actionType = selectedType
                        state.inputText = null
                        invalidate()
                    }
                }
                .show()
        }

        private fun showInputTextDialog(state: SelectionState) {
            val input = EditText(this@CaptureEditorActivity)
            input.hint = "Enter text to type..."
            state.inputText?.let { input.setText(it) }

            AlertDialog.Builder(this@CaptureEditorActivity)
                .setTitle("Input Text")
                .setView(input)
                .setPositiveButton("OK") { _, _ ->
                    state.actionType = ActionType.INPUT_TEXT
                    state.inputText = input.text.toString()
                    invalidate()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun toggleSelection(element: UIElement) {
            val existing = selectionStates.find { it.element.id == element.id }
            if (existing != null) {
                selectionStates.remove(existing)
            } else {
                selectionStates.add(SelectionState(element))
            }
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = MeasureSpec.getSize(heightMeasureSpec)
            
            // Calculate scale to fit CENTER_INSIDE
            val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val dstRatio = width.toFloat() / height.toFloat()
            
            if (srcRatio > dstRatio) {
                scaleFactor = width.toFloat() / bitmap.width.toFloat()
            } else {
                scaleFactor = height.toFloat() / bitmap.height.toFloat()
            }
            
            offsetX = (width - bitmap.width * scaleFactor) / 2
            offsetY = (height - bitmap.height * scaleFactor) / 2
            
            setMeasuredDimension(width, height)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            // Draw Bitmap scaled
            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scaleFactor, scaleFactor)
            
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            
            // Draw Elements
            for (element in elements) {
                val state = selectionStates.find { it.element.id == element.id }
                val index = selectionStates.indexOf(state)
                val isSelected = index != -1
                
                if (isSelected && state != null) {
                    val paint = if (state.isOptional) paintDashed else paintSelected
                    canvas.drawRect(element.bounds, paint)
                    
                    val cx = element.bounds.left
                    val cy = element.bounds.top
                    
                    canvas.drawCircle(cx, cy, 25f / scaleFactor, paintBadge)
                    
                    // Draw Index
                    paintBadgeText.textSize = 30f / scaleFactor
                    val textY = cy - (paintBadgeText.descent() + paintBadgeText.ascent()) / 2
                    canvas.drawText((index + 1).toString(), cx, textY, paintBadgeText)
                    
                    // Draw Action Badge
                    val badgeIcon = when (state.actionType) {
                        ActionType.CLICK -> "👆"
                        ActionType.SCROLL_UP -> "⬆️"
                        ActionType.SCROLL_DOWN -> "⬇️"
                        ActionType.INPUT_TEXT -> "⌨️"
                        ActionType.WAIT -> "⏱️"
                        else -> ""
                    }
                    if (badgeIcon.isNotEmpty()) {
                        val paintIcon = Paint(paintBadgeText).apply { textAlign = Paint.Align.LEFT }
                        canvas.drawText(badgeIcon, cx + 60f / scaleFactor, textY, paintIcon)
                        
                        if (state.actionType == ActionType.INPUT_TEXT && state.inputText != null) {
                           canvas.drawText("\"${state.inputText}\"", cx + 100f / scaleFactor, textY, paintIcon)
                        }
                    }
                } else {
                    canvas.drawRect(element.bounds, paintBox)
                }
            }
            
            canvas.restore()
        }
    }
}
