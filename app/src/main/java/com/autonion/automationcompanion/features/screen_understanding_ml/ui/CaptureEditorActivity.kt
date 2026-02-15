package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.addCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.autonion.automationcompanion.features.screen_understanding_ml.core.PerceptionLayer
import com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import com.autonion.automationcompanion.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class CaptureEditorActivity : ComponentActivity() {

    private var sourceBitmap: Bitmap? = null
    private var editorViewInput: EditorView? = null // Reference to AndroidView to get data
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
        
        // Handle Back Press explicitly
        onBackPressedDispatcher.addCallback(this) {
            handleExit()
        }

        setContent {
            AppTheme {
                CaptureEditorScreen()
            }
        }
        
        runDetection()
    }
    
    @Composable
    fun CaptureEditorScreen() {
        // Keep screen black behind image for contrast
        Scaffold(
            containerColor = Color.Black,
            bottomBar = {
                EditorBottomBar(
                    onCancel = { handleExit() },
                    onRetake = { handleRetake() },
                    onAdd = { addToPreset() }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (sourceBitmap != null) {
                    AndroidView(
                        factory = { context ->
                            EditorView(context, sourceBitmap!!).also { editorViewInput = it }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    @Composable
    fun EditorBottomBar(
        onCancel: () -> Unit,
        onRetake: () -> Unit,
        onAdd: () -> Unit
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Cancel")
                }

                OutlinedButton(
                    onClick = onRetake,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Retake")
                }

                Button(
                    onClick = onAdd,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Step")
                }
            }
        }
    }

    private fun handleExit() {
        finish() 
    }

    private fun handleRetake() {
        Toast.makeText(this, "Tap Snap again to retake", Toast.LENGTH_SHORT).show()
        finish()
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
                     editorViewInput?.setElements(elements)
                 }
             }
        }
    }
    
    private fun addToPreset() {
        val view = editorViewInput ?: return
        
        val selected = view.getSelectedElements()
        val configs = view.getSelectionConfig()
        val actionTypes = view.getSelectionActionTypes()
        val inputTexts = view.getSelectionInputTexts()
        
        if (selected.isEmpty()) {
            Toast.makeText(this, "Please select at least one element", Toast.LENGTH_SHORT).show()
            return
        }
        
        val count = selected.size
        
        val steps = (0 until count).map { i ->
             AutomationStep(
                 id = UUID.randomUUID().toString(),
                 orderIndex = i,
                 label = selected[i].label,
                 anchor = selected[i],
                 isOptional = configs.getOrElse(i) { false },
                 actionType = actionTypes.getOrElse(i) { ActionType.CLICK },
                 inputText = inputTexts.getOrElse(i) { null }
             )
        }
        
        val service = ScreenUnderstandingService.instance
        if (service != null) {
            service.addStepsFromEditor(steps)
            finish()
        } else {
            Toast.makeText(this, "Error: Service not running! Elements lost.", Toast.LENGTH_LONG).show()
            android.util.Log.e("CaptureEditor", "Service instance is null")
        }
    }
    
    override fun onResume() {
        super.onResume()
        ScreenUnderstandingService.instance?.setOverlayVisibility(false)
    }

    override fun onPause() {
        super.onPause()
        ScreenUnderstandingService.instance?.setOverlayVisibility(true)
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

    private inner class EditorView(context: Context, val bitmap: Bitmap) : View(context) {
        
        private val paintBox = Paint().apply {
            color = android.graphics.Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val paintSelected = Paint().apply {
            color = android.graphics.Color.BLUE 
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        private val paintBadge = Paint().apply {
            color = android.graphics.Color.BLUE
            style = Paint.Style.FILL
        }
        private val paintBadgeText = Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val paintDashed = Paint().apply {
            color = android.graphics.Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 8f
            pathEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
        }
        
        private var elements: List<UIElement> = emptyList()
        private val selectionStates: MutableList<SelectionState> = mutableListOf()
        
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
            
            canvas.save()
            canvas.translate(offsetX, offsetY)
            canvas.scale(scaleFactor, scaleFactor)
            
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            
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
                    
                    paintBadgeText.textSize = 30f / scaleFactor
                    val textY = cy - (paintBadgeText.descent() + paintBadgeText.ascent()) / 2
                    canvas.drawText((index + 1).toString(), cx, textY, paintBadgeText)
                    
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
