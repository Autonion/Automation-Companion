package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.autonion.automationcompanion.features.screen_understanding_ml.core.OcrEngine
import com.autonion.automationcompanion.features.screen_understanding_ml.core.PerceptionLayer
import com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService
import com.autonion.automationcompanion.features.screen_understanding_ml.model.ActionType
import com.autonion.automationcompanion.features.screen_understanding_ml.model.AutomationStep
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement
import com.autonion.automationcompanion.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract

enum class EditorDisplayMode { ELEMENTS, TEXT }

class CaptureEditorActivity : ComponentActivity() {

    private var sourceBitmap: Bitmap? = null
    private var editorViewInput: EditorView? = null
    private var perceptionLayer: PerceptionLayer? = null
    private var presetName: String = "Untitled"
    
    // OCR state
    private var ocrElements: List<UIElement>? = null // Cached OCR results
    
    // Flow mode state
    private var isFlowMode = false
    private var flowNodeId: String? = null
    private var currentImagePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val imagePath = intent.getStringExtra("IMAGE_PATH")
        presetName = intent.getStringExtra("PRESET_NAME") ?: "Untitled"
        isFlowMode = intent.getBooleanExtra(FlowOverlayContract.EXTRA_FLOW_MODE, false)
        flowNodeId = intent.getStringExtra(FlowOverlayContract.EXTRA_FLOW_NODE_ID)
        currentImagePath = imagePath
        
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
        var displayMode by remember { mutableStateOf(EditorDisplayMode.ELEMENTS) }
        var ocrLoading by remember { mutableStateOf(false) }

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

                // ── Tab toggle: Elements / Text ──
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(Color(0xCC1A1A2E), RoundedCornerShape(24.dp))
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = displayMode == EditorDisplayMode.ELEMENTS,
                        onClick = {
                            displayMode = EditorDisplayMode.ELEMENTS
                            editorViewInput?.setDisplayMode(EditorDisplayMode.ELEMENTS)
                        },
                        label = { Text("🔲 Elements", fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF3D5AFE),
                            selectedLabelColor = Color.White,
                            containerColor = Color.Transparent,
                            labelColor = Color.White.copy(alpha = 0.7f)
                        )
                    )
                    FilterChip(
                        selected = displayMode == EditorDisplayMode.TEXT,
                        onClick = {
                            displayMode = EditorDisplayMode.TEXT
                            editorViewInput?.setDisplayMode(EditorDisplayMode.TEXT)
                            // Run OCR on first switch (cached after that)
                            if (ocrElements == null && !ocrLoading) {
                                ocrLoading = true
                                runOcr { ocrLoading = false }
                            }
                        },
                        label = {
                            if (ocrLoading) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Scanning...", fontSize = 13.sp)
                                }
                            } else {
                                Text("📝 Text", fontSize = 13.sp)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00BCD4),
                            selectedLabelColor = Color.White,
                            containerColor = Color.Transparent,
                            labelColor = Color.White.copy(alpha = 0.7f)
                        )
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
                    .padding(horizontal = 8.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", maxLines = 1)
                }

                OutlinedButton(
                    onClick = onRetake,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retake", maxLines = 1)
                }

                Button(
                    onClick = onAdd,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", maxLines = 1)
                }
            }
        }
    }

    private fun handleExit() {
        // Stop service (overlay + projection) on Cancel
        ScreenUnderstandingService.instance?.stopSelf()
        finish() 
    }

    private fun handleRetake() {
        Toast.makeText(this, "Tap Snap again to retake", Toast.LENGTH_SHORT).show()
        // Improve UX: Minimize app to go back to target app immediately, then close this activity
        moveTaskToBack(true)
        finish()
    }
    
    private fun runDetection() {
        val flowMlJson = intent.getStringExtra("EXTRA_FLOW_ML_JSON")
        Toast.makeText(this, "Detecting UI elements...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.Default) {
             val detectedElements = perceptionLayer?.detect(sourceBitmap!!) ?: emptyList()
             withContext(Dispatchers.Main) {
                 val finalElements = detectedElements.toMutableList()
                 var loadedStates: List<SelectionState>? = null

                 if (!flowMlJson.isNullOrEmpty()) {
                     try {
                         val steps = Json.decodeFromString<List<AutomationStep>>(flowMlJson)
                         val savedElements = steps.map { it.anchor }
                         for (saved in savedElements) {
                             if (finalElements.none { it.id == saved.id }) {
                                 finalElements.add(saved)
                             }
                         }
                         loadedStates = steps.map { step ->
                             SelectionState(
                                 element = step.anchor,
                                 isOptional = step.isOptional,
                                 actionType = step.actionType,
                                 inputText = step.inputText
                             )
                         }
                     } catch (e: Exception) {
                         android.util.Log.e("CaptureEditor", "Failed to parse EXTRA_FLOW_ML_JSON", e)
                     }
                 }

                 if (finalElements.isEmpty()) {
                     Toast.makeText(this@CaptureEditorActivity, "No elements found — try Retake", Toast.LENGTH_SHORT).show()
                 } else {
                     Toast.makeText(this@CaptureEditorActivity, "Found ${finalElements.size} elements", Toast.LENGTH_SHORT).show()
                     editorViewInput?.setElements(finalElements)
                     loadedStates?.let { editorViewInput?.setSelectionStates(it) }
                 }
             }
        }
    }

    private fun runOcr(onComplete: () -> Unit) {
        val bitmap = sourceBitmap ?: return
        lifecycleScope.launch(Dispatchers.Default) {
            val ocrEngine = OcrEngine()
            try {
                val result = ocrEngine.recognizeText(bitmap)
                val elements = result.blocks.mapNotNull { block ->
                    val bounds = block.bounds ?: return@mapNotNull null
                    UIElement(
                        id = UUID.randomUUID().toString(),
                        label = "Text",
                        confidence = block.confidence ?: 0.9f,
                        bounds = bounds,
                        text = block.text
                    )
                }
                ocrElements = elements
                withContext(Dispatchers.Main) {
                    if (elements.isEmpty()) {
                        Toast.makeText(this@CaptureEditorActivity, "No text found on screen", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@CaptureEditorActivity, "Found ${elements.size} text blocks", Toast.LENGTH_SHORT).show()
                    }
                    editorViewInput?.setOcrElements(elements)
                    onComplete()
                }
            } catch (e: Exception) {
                android.util.Log.e("CaptureEditor", "OCR failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CaptureEditorActivity, "OCR failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            } finally {
                ocrEngine.close()
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
        
        if (isFlowMode && flowNodeId != null) {
            try {
                val json = Json.encodeToString(steps)
                val tempFile = File(cacheDir, "flow_ml_${flowNodeId}.json")
                tempFile.writeText(json)
                
                val resultIntent = Intent(FlowOverlayContract.ACTION_FLOW_ML_DONE).apply {
                    putExtra(FlowOverlayContract.EXTRA_RESULT_NODE_ID, flowNodeId)
                    putExtra(FlowOverlayContract.EXTRA_RESULT_FILE_PATH, tempFile.absolutePath)
                    putExtra(FlowOverlayContract.EXTRA_RESULT_IMAGE_PATH, currentImagePath)
                }
                LocalBroadcastManager.getInstance(this).sendBroadcast(resultIntent)
                
                Toast.makeText(this, "Flow node configured", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "Error saving for flow mode: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        val service = ScreenUnderstandingService.instance
        if (service != null) {
            service.addStepsFromEditor(steps)
            // Go back to target app to continue capturing, then close this activity
            moveTaskToBack(true)
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

        // ── Element mode paints ──
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

        // ── Text/OCR mode paints ──
        private val paintOcrBox = Paint().apply {
            color = 0xFF00BCD4.toInt() // Cyan
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val paintOcrSelected = Paint().apply {
            color = 0xFF00BCD4.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        private val paintOcrLabelBg = Paint().apply {
            color = 0xCC1A1A2E.toInt()
            style = Paint.Style.FILL
        }
        private val paintOcrLabelText = Paint().apply {
            color = 0xFFE0F7FA.toInt()
            textSize = 24f
            style = Paint.Style.FILL
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        private var displayMode: EditorDisplayMode = EditorDisplayMode.ELEMENTS
        private var elements: List<UIElement> = emptyList()
        private var ocrTextElements: List<UIElement> = emptyList()
        private val selectionStates: MutableList<SelectionState> = mutableListOf()

        private var scaleFactor = 1f
        private var offsetX = 0f
        private var offsetY = 0f

        /** Currently active elements based on display mode */
        private val activeElements: List<UIElement>
            get() = when (displayMode) {
                EditorDisplayMode.ELEMENTS -> elements
                EditorDisplayMode.TEXT -> ocrTextElements
            }

        fun setElements(newElements: List<UIElement>) {
            elements = newElements
            invalidate()
        }

        fun setOcrElements(newElements: List<UIElement>) {
            ocrTextElements = newElements
            if (displayMode == EditorDisplayMode.TEXT) invalidate()
        }

        fun setDisplayMode(mode: EditorDisplayMode) {
            displayMode = mode
            invalidate()
        }

        fun setSelectionStates(states: List<SelectionState>) {
            selectionStates.clear()
            selectionStates.addAll(states)
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

                val clicked = activeElements.find { it.bounds.contains(touchX, touchY) }
                if (clicked != null) {
                    toggleSelection(clicked)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val touchX = (e.x - offsetX) / scaleFactor
                val touchY = (e.y - offsetY) / scaleFactor

                val clicked = activeElements.find { it.bounds.contains(touchX, touchY) }
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

            when (displayMode) {
                EditorDisplayMode.ELEMENTS -> drawElementOverlays(canvas)
                EditorDisplayMode.TEXT -> drawTextOverlays(canvas)
            }

            canvas.restore()
        }

        /** Draw TFLite-detected UI element bounding boxes (green/blue) */
        private fun drawElementOverlays(canvas: Canvas) {
            for (element in elements) {
                val state = selectionStates.find { it.element.id == element.id }
                val index = selectionStates.indexOf(state)
                val isSelected = index != -1

                if (isSelected && state != null) {
                    val paint = if (state.isOptional) paintDashed else paintSelected
                    canvas.drawRect(element.bounds, paint)
                    drawSelectionBadge(canvas, element, state, index)
                } else {
                    canvas.drawRect(element.bounds, paintBox)
                }
            }
        }

        /** Draw OCR text blocks (thin cyan borders + small text labels below) */
        private fun drawTextOverlays(canvas: Canvas) {
            val invScale = 1f / scaleFactor
            paintOcrLabelText.textSize = 24f * invScale

            for (element in ocrTextElements) {
                val state = selectionStates.find { it.element.id == element.id }
                val index = selectionStates.indexOf(state)
                val isSelected = index != -1

                if (isSelected && state != null) {
                    // Selected: thicker cyan border + badge
                    canvas.drawRect(element.bounds, paintOcrSelected)
                    drawSelectionBadge(canvas, element, state, index)
                } else {
                    // Unselected: thin cyan border
                    canvas.drawRect(element.bounds, paintOcrBox)
                }

                // Text label below bounding box (non-obstructive)
                val labelText = element.text?.take(40) ?: ""
                if (labelText.isNotEmpty()) {
                    val labelX = element.bounds.left
                    val labelY = element.bounds.bottom + 22f * invScale
                    val textWidth = paintOcrLabelText.measureText(labelText)
                    val pad = 4f * invScale

                    // Semi-transparent background pill
                    canvas.drawRoundRect(
                        RectF(
                            labelX - pad,
                            labelY - paintOcrLabelText.textSize - pad,
                            labelX + textWidth + pad,
                            labelY + pad
                        ),
                        6f * invScale, 6f * invScale,
                        paintOcrLabelBg
                    )
                    canvas.drawText(labelText, labelX, labelY, paintOcrLabelText)
                }
            }
        }

        /** Draw numbered selection badge + action icon for a selected element */
        private fun drawSelectionBadge(canvas: Canvas, element: UIElement, state: SelectionState, index: Int) {
            val cx = element.bounds.left
            val cy = element.bounds.top

            canvas.drawCircle(cx, cy, 25f / scaleFactor, paintBadge)

            paintBadgeText.textSize = 30f / scaleFactor
            val textY = cy - (paintBadgeText.descent() + paintBadgeText.ascent()) / 2
            canvas.drawText((index + 1).toString(), cx, textY, paintBadgeText)

            val badgeIcon = when (state.actionType) {
                ActionType.CLICK -> "\uD83D\uDC46"
                ActionType.SCROLL_UP -> "\u2B06\uFE0F"
                ActionType.SCROLL_DOWN -> "\u2B07\uFE0F"
                ActionType.INPUT_TEXT -> "\u2328\uFE0F"
                ActionType.WAIT -> "\u23F1\uFE0F"
                else -> ""
            }
            if (badgeIcon.isNotEmpty()) {
                val paintIcon = Paint(paintBadgeText).apply { textAlign = Paint.Align.LEFT }
                canvas.drawText(badgeIcon, cx + 60f / scaleFactor, textY, paintIcon)

                if (state.actionType == ActionType.INPUT_TEXT && state.inputText != null) {
                    canvas.drawText("\"${state.inputText}\"", cx + 100f / scaleFactor, textY, paintIcon)
                }
            }
        }
    }
}
