package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.autonion.automationcompanion.features.screen_understanding_ml.model.UIElement

class ScreenAgentOverlay(
    private val context: Context,
    private val initialName: String? = null,
    private val onAnchorSelected: (UIElement) -> Unit,
    private val onSave: (String, List<Pair<UIElement, Boolean>>) -> Unit, // Name + Element + IsOptional
    private val onPlay: () -> Unit,
    private val onStop: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: OverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    
    // Control Window
    private var controlsView: android.view.ViewGroup? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    
    private var isInspectionMode = false

    // Moved SelectionState here so it is accessible if needed, 
    // but OverlayView needs it key. 
    // Actually, let's keep it simple. 
    // The previous error was likely due to incomplete edits leaving 'class' keyword inside function or similar.
    // And 'selectionStates' not being visible to 'ScreenAgentOverlay' methods like 'setupControls'.
    
    // To fix 'unresolved reference selectionStates' in setupControls (btnSave callback),
    // we need to expose methods on OverlayView to get the data, which we did (getSelectedElements, getSelectionConfig).
    // The previous edit likely created a mismatch or syntax error.
    
    fun show() {
        if (overlayView != null) return

        // 1. Drawing Window (Fullscreen)
        overlayView = OverlayView(context)
        layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            // Default: Passthrough
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.START or Gravity.TOP
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        windowManager.addView(overlayView, layoutParams)
        
        // 2. Control Window (Floating)
        setupControls()
    }


    private fun setupControls() {
        // Icon-based Compact Control Bar
        val panel = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            // Use drag_handle_bg if available, otherwise fallback. 
            // We'll use a semi-transparent dark background with rounded corners programmatically if needed,
            // or try standard resource.
            // setBackgroundResource(com.autonion.automationcompanion.R.drawable.drag_handle_bg) // Assuming this exists or similar
            setBackgroundColor(Color.parseColor("#99000000")) 
            setPadding(8, 8, 8, 8)
        }
        
        fun createIconButton(iconRes: Int, desc: String, onClick: () -> Unit): android.widget.ImageButton {
            return android.widget.ImageButton(context).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setBackgroundResource(com.autonion.automationcompanion.R.drawable.overlay_button_bg)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(16, 16, 16, 16)
                layoutParams = android.widget.LinearLayout.LayoutParams(120, 120).apply {
                    setMargins(8, 0, 8, 0)
                }
                setOnClickListener { onClick() }
            }
        }
        
        // 1. Inspect / Interact
        val btnInspect = createIconButton(com.autonion.automationcompanion.R.drawable.ic_touch_app, "Inspect") {
            toggleInspectionMode()
        }
        
        // 2. Mode (Strict/Optional)
        val btnMode = createIconButton(com.autonion.automationcompanion.R.drawable.ic_setup, "Mode: Strict") {
            overlayView?.toggleLastSelectionMode()
        }
        
        // 3. Play
        val btnPlay = createIconButton(com.autonion.automationcompanion.R.drawable.ic_play, "Play") {
             android.widget.Toast.makeText(context, "Playback not implemented yet", android.widget.Toast.LENGTH_SHORT).show()
        }

        // 4. Save
        val btnSave = createIconButton(com.autonion.automationcompanion.R.drawable.ic_save, "Save") {
             val elements = overlayView?.getSelectedElements() ?: emptyList()
             val configs = overlayView?.getSelectionConfig() ?: emptyList()
             
             if (elements.isEmpty()) {
                 android.widget.Toast.makeText(context, "No elements to save", android.widget.Toast.LENGTH_SHORT).show()
                 return@createIconButton
             }
             
             // If we already have a name from SetupFlow, use it directly
             if (!initialName.isNullOrBlank()) {
                 if (elements.size == configs.size) {
                     val zipped = elements.zip(configs)
                     onSave(initialName, zipped)
                 }
             } else {
                 showSaveDialog()
             }
        }
        
        // 5. Stop
        val btnStop = createIconButton(com.autonion.automationcompanion.R.drawable.ic_close, "Stop/Close") { onStop() }
        
        panel.addView(btnInspect)
        panel.addView(btnMode)
        panel.addView(btnPlay)
        panel.addView(btnSave)
        panel.addView(btnStop)
        
        controlsView = panel
        
        // Keep reference to update state
        btnInspectRef = btnInspect
        btnModeRef = btnMode
        
        controlsParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START // Changed to Start for manual positioning
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = 50
            y = 200 
        }
        
        // Make Draggable using existing OverlayTouchListener
        panel.setOnTouchListener(
            com.autonion.automationcompanion.features.gesture_recording_playback.overlay.OverlayTouchListener(
                windowManager, 
                controlsView!!, 
                controlsParams!!
            )
        )
        
        windowManager.addView(controlsView, controlsParams)
    }

    private var saveDialogView: View? = null

    private fun showSaveDialog() {
        if (saveDialogView != null) return

        val dialogView = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            // Add rounded corners if possible, or validation
        }

        val title = android.widget.TextView(context).apply {
            text = "Save Preset"
            textSize = 18f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 16)
        }

        val input = android.widget.EditText(context).apply {
            hint = "Enter preset name"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
            textSize = 16f
        }

        val buttons = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 16, 0, 0)
        }

        val btnCancel = android.widget.Button(context).apply {
            text = "Cancel"
            setOnClickListener { dismissSaveDialog() }
        }

        val btnConfirm = android.widget.Button(context).apply {
            text = "Save"
            setOnClickListener {
                val name = input.text.toString()
                if (name.isNotBlank()) {
                     val elements = overlayView?.getSelectedElements() ?: emptyList()
                     val configs = overlayView?.getSelectionConfig() ?: emptyList()
                     
                     if (elements.size == configs.size) {
                         val zipped = elements.zip(configs)
                         onSave(name, zipped) // Updated signature
                     }
                     dismissSaveDialog()
                } else {
                    android.widget.Toast.makeText(context, "Name cannot be empty", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        buttons.addView(btnCancel)
        buttons.addView(btnConfirm)
        
        dialogView.addView(title)
        dialogView.addView(input)
        dialogView.addView(buttons)

        saveDialogView = dialogView

        val params = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            dimAmount = 0.5f
            
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.CENTER
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

        windowManager.addView(saveDialogView, params)
    }

    private fun dismissSaveDialog() {
        if (saveDialogView != null) {
            windowManager.removeView(saveDialogView)
            saveDialogView = null
        }
    }

    private var btnInspectRef: android.widget.ImageButton? = null
    private var btnModeRef: android.widget.ImageButton? = null
    
    fun updateModeButton(isOptional: Boolean) {
        val color = if (isOptional) Color.YELLOW else Color.WHITE
        btnModeRef?.setColorFilter(color)
        btnModeRef?.contentDescription = if (isOptional) "Mode: Optional" else "Mode: Strict"
        
        // Optional: Show toast if changed
        // android.widget.Toast.makeText(context, if(isOptional) "Optional" else "Strict", android.widget.Toast.LENGTH_SHORT).show()
    }


    private fun toggleInspectionMode() {
        isInspectionMode = !isInspectionMode
        // btnInspectRef?.text = if (isInspectionMode) "Resume" else "Inspect"
        val color = if (isInspectionMode) Color.GREEN else Color.WHITE
        btnInspectRef?.setColorFilter(color)
        
        layoutParams?.let { params ->
            if (isInspectionMode) {
                // Enable touch
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                android.util.Log.d("ScreenAgentOverlay", "Inspection Mode ON: Touch enabled")
            } else {
                // Disable touch (Passthrough)
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                android.util.Log.d("ScreenAgentOverlay", "Inspection Mode OFF: Touch disabled")
            }
            windowManager.updateViewLayout(overlayView, params)
        }
        
        overlayView?.setValidationMode(isInspectionMode)
    }

    fun dismiss() {
        if (overlayView != null) {
            windowManager.removeView(overlayView)
            overlayView = null
        }
        if (controlsView != null) {
            windowManager.removeView(controlsView)
            controlsView = null
        }
    }

    // fun getOverlayView() = overlayView // Removed to keep OverlayView private

    fun getSelectedElements(): List<UIElement> = overlayView?.getSelectedElements() ?: emptyList()
    
    fun getSelectionConfig(): List<Boolean> = overlayView?.getSelectionConfig() ?: emptyList()

    fun updateElements(elements: List<UIElement>) {
        if (!isInspectionMode) {
             overlayView?.setElements(elements)
        }
    }
    
    data class SelectionState(
         val element: UIElement,
         var isOptional: Boolean = false
    )
    private inner class OverlayView(context: Context) : View(context) {
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
        private val paintText = Paint().apply {
            color = Color.GREEN
            textSize = 30f
            style = Paint.Style.FILL
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
        private val paintDim = Paint().apply {
             color = Color.parseColor("#40000000")
             style = Paint.Style.FILL
        }
        private val paintDashed = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.STROKE
            strokeWidth = 8f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 20f), 0f)
        }

        // Wrapper to store selection state
        // NOTE: data class must be declared properly
        // We cannot use 'SelectionState' inside 'OverlayView' if we try to access it outside easily 
        // without qualification, but here it is private internal logic.
        
        private var elements: List<UIElement> = emptyList()
        private val selectionStates: MutableList<SelectionState> = mutableListOf() 
        private var validationMode = false

        fun setElements(newElements: List<UIElement>) {
            if (validationMode) return 
            elements = newElements
            invalidate()
        }
        
        fun setValidationMode(enabled: Boolean) {
            validationMode = enabled
            if (!enabled) selectionStates.clear() 
            invalidate()
        }
        
        fun getSelectedElements(): List<UIElement> = selectionStates.map { it.element }
        
        fun getSelectionConfig(): List<Boolean> = selectionStates.map { it.isOptional }
        
        fun toggleLastSelectionMode() {
            if (selectionStates.isNotEmpty()) {
                val last = selectionStates.last()
                last.isOptional = !last.isOptional
                updateModeButton(last.isOptional) 
                invalidate()
            }
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (!validationMode) return super.onTouchEvent(event)
            
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y
                
                val clicked = elements.find { it.bounds.contains(x, y) }
                if (clicked != null) {
                     toggleSelection(clicked)
                }
                return true
            }
            return super.onTouchEvent(event)
        }
        
        private fun toggleSelection(element: UIElement) {
            val existing = selectionStates.find { it.element.id == element.id }
            if (existing != null) {
                selectionStates.remove(existing)
            } else {
                selectionStates.add(SelectionState(element, isOptional = false))
            }
            invalidate()
            
            if (selectionStates.isNotEmpty()) {
                updateModeButton(selectionStates.last().isOptional)
            } else {
                 updateModeButton(false) // Default strict if empty
            }
            onAnchorSelected(element) 
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            
            if (validationMode) {
                 canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintDim)
            }

            for (element in elements) {
                val state = selectionStates.find { it.element.id == element.id }
                val index = selectionStates.indexOf(state)
                val isSelected = index != -1
                
                if (isSelected) {
                    val paint = if (state?.isOptional == true) paintDashed else paintSelected
                    canvas.drawRect(element.bounds, paint)
                    
                    val radius = 25f
                    val cx = element.bounds.left
                    val cy = element.bounds.top
                    
                    canvas.drawCircle(cx, cy, radius, paintBadge)
                    val textY = cy - (paintBadgeText.descent() + paintBadgeText.ascent()) / 2
                    canvas.drawText((index + 1).toString(), cx, textY, paintBadgeText)
                    
                } else {
                    canvas.drawRect(element.bounds, paintBox)
                    // ... text ...
                }
            }
        }
    }
    
    
    // Moved out of inner class to be safe and clear

}

