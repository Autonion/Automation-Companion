package com.autonion.automationcompanion.features.gesture_recording_playback.managers

import android.annotation.SuppressLint
import android.graphics.PointF
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.features.gesture_recording_playback.models.Action
import com.autonion.automationcompanion.features.gesture_recording_playback.models.ActionType
import com.autonion.automationcompanion.features.gesture_recording_playback.views.ActionMarker
import com.autonion.automationcompanion.features.gesture_recording_playback.views.ConnectionLineView

private data class ActionVisuals(val actionId: Int, val markers: List<ActionMarker>, val line: ConnectionLineView?)

object ActionManager {

    interface FocusListener {
        fun onFocusRequired()
        fun onFocusReleased()
        fun onRequestFocus(view: View)
    }

    interface ActionCountListener {
        fun onActionCountChanged(newCount: Int)
    }

    private const val CONFIRMATION_VIEW_TAG = "ActionManager.ConfirmationView"

    private val actions = mutableListOf<Action>()
    private var nextId = 1
    private val actionVisuals = mutableListOf<ActionVisuals>()

    private var isSetupMode = false

    // For step-by-step creation
    private var pendingAction: Action? = null
    private var pendingMarkers = mutableListOf<ActionMarker>()
    private var pendingLine: ConnectionLineView? = null

    private var focusListener: FocusListener? = null
    private var actionCountListener: ActionCountListener? = null

    fun setFocusListener(listener: FocusListener) {
        focusListener = listener
    }

    fun setActionCountListener(listener: ActionCountListener) {
        actionCountListener = listener
    }

    fun startSetupMode() {
        isSetupMode = true
        actionVisuals.forEach { visual ->
            visual.markers.forEach { it.isVisible = true }
            visual.line?.visibility = View.VISIBLE
        }
    }

    fun endSetupMode() {
        isSetupMode = false
        actionVisuals.forEach { visual ->
            visual.markers.forEach { it.isVisible = false }
            visual.line?.visibility = View.GONE
        }
        if (pendingAction != null) {
            // Try to determine a parent container for the confirmation view.
            // If creation flow was used, pendingMarkers will contain markers whose parent is the container.
            // If editing flow was used, pendingMarkers may be empty, so use the visual markers' parent instead.
            val containerFromPending = pendingMarkers.firstOrNull()?.parent as? ViewGroup
            val containerFromVisual = actionVisuals.find { it.actionId == pendingAction?.id }?.markers?.firstOrNull()?.parent as? ViewGroup
            val container = containerFromPending ?: containerFromVisual

            // Fallback: if still null, try to remove confirmation from any visual marker parent
            if (container == null) {
                // attempt to remove confirmation from all known visuals' parents
                actionVisuals.mapNotNull { it.markers.firstOrNull()?.parent as? ViewGroup }.forEach { cancelPending(it) }
            } else {
                cancelPending(container)
            }
        }
    }

    fun addNewClick(container: ViewGroup) {
        startCreationStep(container, ActionType.CLICK)
    }

    fun addNewSwipe(container: ViewGroup) {
        startCreationStep(container, ActionType.SWIPE)
    }

    fun addNewLongClick(container: ViewGroup) {
        startCreationStep(container, ActionType.LONG_CLICK)
    }

    fun loadActions(newActions: List<Action>) {
        clearAllActions() // Now only clears data
        actions.addAll(newActions)
        // Update nextId to avoid conflicts
        nextId = (actions.maxOfOrNull { it.id } ?: 0) + 1
    }

    private fun startCreationStep(container: ViewGroup, type: ActionType) {
        focusListener?.onFocusRequired()
        isSetupMode = true
        cancelPending(container)

        val startX = container.width / 2f
        val startY = container.height / 2f
        val startPoint = PointF(startX, startY)

        // LONG_CLICK needs a longer duration (default 1500ms for comfortable long press)
        val defaultDuration = if (type == ActionType.LONG_CLICK) 1500L else 100L
        pendingAction = Action(id = -1, type = type, points = mutableListOf(startPoint), duration = defaultDuration)

        val label = if (type == ActionType.CLICK) "Click $nextId" else "$type Start"

        val startMarker = createMarker(container, startPoint, label, onMove = { screenX, screenY ->
            updatePendingActionPoint(0, screenX, screenY)
        })
        pendingMarkers.add(startMarker)

        // The initial 'startPoint' is a local coordinate. We must immediately convert and
        // update the pendingAction to use absolute screen coordinates. Otherwise, if the user
        // confirms without moving the marker, the local coordinate gets saved by mistake.
        startMarker.post {
            val markerLocation = IntArray(2)
            startMarker.getLocationOnScreen(markerLocation)
            val screenX = markerLocation[0] + startMarker.width / 2f
            val screenY = markerLocation[1] + startMarker.height / 2f
            updatePendingActionPoint(0, screenX, screenY)
            Log.d("MARKER_DEBUG", "Initial marker created at screen coords: $screenX, $screenY")
        }

        if (type == ActionType.CLICK || type == ActionType.LONG_CLICK) {
            showConfirmation(container, pendingAction) {
                finishCreation(container)
            }
        } else {
            proceedToStep2(container, type, startMarker)
        }
    }


    private fun proceedToStep2(container: ViewGroup, type: ActionType, startMarker: ActionMarker) {
        val startPoint = PointF(startMarker.x, startMarker.y)
        val endPoint = PointF(startPoint.x + 200f, startPoint.y + 200f)

        val currentPoints = pendingAction!!.points.toMutableList()
        currentPoints.add(endPoint)
        pendingAction = pendingAction!!.copy(points = currentPoints)

        val lineView = ConnectionLineView(container.context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        container.addView(lineView, 0)
        pendingLine = lineView

        val endMarker = createMarker(container, endPoint, "$type End")
        pendingMarkers.add(endMarker)

        fun refreshLine() {
            val sx = startMarker.x + startMarker.width / 2f
            val sy = startMarker.y + startMarker.height / 2f
            val ex = endMarker.x + endMarker.width / 2f
            val ey = endMarker.y + endMarker.height / 2f
            pendingLine?.updatePosition(sx, sy, ex, ey)
        }

        // Same as in startCreationStep, we must convert the second marker's initial local
        // position to absolute screen coordinates right away.
        endMarker.post {
            val markerLocation = IntArray(2)
            endMarker.getLocationOnScreen(markerLocation)
            val screenX = markerLocation[0] + endMarker.width / 2f
            val screenY = markerLocation[1] + endMarker.height / 2f
            updatePendingActionPoint(1, screenX, screenY)
            Log.d("MARKER_DEBUG", "Second marker created at screen coords: $screenX, $screenY")

            // Also refresh the line now that we have accurate screen points.
            refreshLine()
        }

        startMarker.setOnPositionChanged { localX, localY ->
            // Convert local coordinates to screen coordinates
            val location = IntArray(2)
            startMarker.parent?.let { parent ->
                (parent as? View)?.getLocationOnScreen(location)
            }
            val screenX = localX + location[0]
            val screenY = localY + location[1]
            updatePendingActionPoint(0, screenX, screenY)
            refreshLine()
        }
        endMarker.setOnPositionChanged { localX, localY ->
            // Convert local coordinates to screen coordinates
            val location = IntArray(2)
            endMarker.parent?.let { parent ->
                (parent as? View)?.getLocationOnScreen(location)
            }
            val screenX = localX + location[0]
            val screenY = localY + location[1]
            updatePendingActionPoint(1, screenX, screenY)
            refreshLine()
        }

        showConfirmation(container, pendingAction) { finishCreation(container) }
    }

    private fun startEditingStep(container: ViewGroup, actionId: Int, clickedMarker: ActionMarker) {
        val action = actions.find { it.id == actionId }
        Log.d("ACTION_MANAGER", "Starting to edit Action ID: $actionId. Action data: $action")

        focusListener?.onFocusRequired()
        isSetupMode = true
        cancelPending(container)

        pendingAction = action ?: return

        val visual = actionVisuals.find { it.actionId == actionId }
        if (visual == null) {
            focusListener?.onFocusReleased()
            return
        }

        visual.line?.bringToFront()
        visual.markers.forEach { it.bringToFront() }
        clickedMarker.bringToFront()

        visual.markers.forEachIndexed { index, marker ->
            marker.setOnPositionChanged { screenX, screenY ->
                updateActionPoint(actionId, index, screenX, screenY)
            }
        }

        showConfirmation(container, action) {
            val index = actions.indexOfFirst { it.id == actionId }
            if (index != -1) {
                actions[index] = pendingAction!!
            }

            visual.markers.forEachIndexed { _, m ->
                m.setOnClickListener {
                    if (pendingAction == null) {
                        startEditingStep(container, actionId, m)
                    }
                }
            }
            removeConfirmation(container)
            focusListener?.onFocusReleased()
            pendingAction = null
        }
    }

    private fun finishCreation(container: ViewGroup) {
        removeConfirmation(container)
        if (pendingAction != null) {
            val finalAction = pendingAction!!.copy(id = nextId++)
            actions.add(finalAction)
            actionCountListener?.onActionCountChanged(actions.size)

            pendingMarkers.forEachIndexed { index, marker ->
                marker.setOnClickListener {
                    if (pendingAction == null) {
                        startEditingStep(container, finalAction.id, marker)
                    }
                }
                marker.setOnPositionChanged { screenX, screenY ->
                    updateActionPoint(finalAction.id, index, screenX, screenY)
                }
            }

            actionVisuals.add(ActionVisuals(finalAction.id, pendingMarkers.toList(), pendingLine))

            pendingAction = null
            pendingMarkers.clear()
            pendingLine = null

            Toast.makeText(container.context, "Action Added", Toast.LENGTH_SHORT).show()
        }
        focusListener?.onFocusReleased()
    }

    private fun cancelPending(container: ViewGroup?) {
        if (container == null) return
        removeConfirmation(container)
        pendingMarkers.forEach { it.removeFromParent() }
        pendingMarkers.clear()
        pendingLine?.let { (it.parent as? ViewGroup)?.removeView(it) }
        pendingLine = null
        pendingAction = null
        focusListener?.onFocusReleased()
    }

    private fun showConfirmation(container: ViewGroup, action: Action?, onConfirm: () -> Unit) {
        removeConfirmation(container)
        val view = LayoutInflater.from(container.context).inflate(R.layout.action_confirmation_view, container, false)
        view.tag = CONFIRMATION_VIEW_TAG
        val btnConfirm = view.findViewById<Button>(R.id.btnConfirm)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val etActionDelay = view.findViewById<EditText>(R.id.etActionDelay)

        val etActionDuration = view.findViewById<EditText>(R.id.etActionDuration)
//        val tvDelay = view.findViewById<android.widget.TextView>(R.id.tvDelay)
        val tvHoldTime = view.findViewById<android.widget.TextView>(R.id.tvHoldTime)

        // For LONG_CLICK, show both duration and delay
        if (action?.type == ActionType.LONG_CLICK) {
            tvHoldTime.visibility = View.VISIBLE
            etActionDuration.visibility = View.VISIBLE
            etActionDuration.setText(action.duration.toString())
            etActionDelay.setText(action.delayAfter.toString())
        } else {
            tvHoldTime.visibility = View.GONE
            etActionDuration.visibility = View.GONE
            etActionDelay.setText(action?.delayAfter?.toString() ?: "500")
        }

        // Make sure the EditTexts can receive focus
        etActionDelay.isFocusable = true
        etActionDelay.isFocusableInTouchMode = true

        etActionDuration.isFocusable = true
        etActionDuration.isFocusableInTouchMode = true

        // Ask the host (OverlayService) to make the overlay window focusable so IME can show
        focusListener?.onFocusRequired()

        // Request focus and IME via the focus listener (do it after the view is attached)
        view.post {
            focusListener?.onRequestFocus(etActionDelay)
        }

        btnConfirm.setOnClickListener {
            val delayText = etActionDelay.text.toString()
            val delayValue = delayText.toLongOrNull() ?: 500L

            if (action?.type == ActionType.LONG_CLICK) {
                val durationText = etActionDuration.text.toString()
                val durationValue = durationText.toLongOrNull() ?: 1500L
                pendingAction = pendingAction?.copy(duration = durationValue, delayAfter = delayValue)
            } else {
                pendingAction = pendingAction?.copy(delayAfter = delayValue)
            }
            // release focus after confirming (host will revert window flags)
            onConfirm()
            focusListener?.onFocusReleased()
        }
        btnCancel.setOnClickListener {
            cancelPending(container)
            if (action?.id != -1) { // existing action
                pendingAction = null
                focusListener?.onFocusReleased()
            }
        }

        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            bottomMargin = 200
        }
        container.addView(view, params)
        makeDraggable(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun makeDraggable(view: View) {
        var startX = 0f
        var startY = 0f
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    startX = v.x
                    startY = v.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY

                    v.x = startX + dx
                    v.y = startY + dy
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // Optional: snap, clamp, or save position here
                    true
                }

                else -> false
            }
        }
    }


    private fun removeConfirmation(container: ViewGroup) {
        container.findViewWithTag<View>(CONFIRMATION_VIEW_TAG)?.let {
            container.removeView(it)
            // make sure host releases focus if confirmation is removed programmatically
            focusListener?.onFocusReleased()
        }
    }


//    private fun removeConfirmation(container: ViewGroup) {
//        container.findViewWithTag<View>(CONFIRMATION_VIEW_TAG)?.let { container.removeView(it) }
//    }

    private fun updatePendingActionPoint(pointIndex: Int, x: Float, y: Float) {
        pendingAction?.let { action ->
            val newPoints = action.points.toMutableList()
            if (pointIndex < newPoints.size) {
                newPoints[pointIndex] = PointF(x, y)
                pendingAction = action.copy(points = newPoints)
            }
        }
    }

    private fun createMarker(
        container: ViewGroup,
        point: PointF,
        label: String,
        onMove: ((Float, Float) -> Unit)? = null
    ): ActionMarker {
        val marker = ActionMarker(container.context).apply {
            setText(label)
            isDraggable = true
            onMove?.let { setOnPositionChanged(it) }
        }
        container.addView(marker)
        marker.post {
            marker.x = point.x - marker.width / 2f
            marker.y = point.y - marker.height / 2f
            Log.d("ACTION_MANAGER", "createMarker: Marker '$label' placed at final coords: (${marker.x}, ${marker.y})")
        }
        return marker
    }

    private fun updateActionPoint(actionId: Int, pointIndex: Int, x: Float, y: Float) {
        val action = actions.find { it.id == actionId } ?: return
        val newPoints = action.points.toMutableList()
        if (pointIndex < newPoints.size) {
            newPoints[pointIndex] = PointF(x, y)
            val updatedAction = action.copy(points = newPoints)
            val index = actions.indexOfFirst { it.id == actionId }
            if (index != -1) {
                actions[index] = updatedAction
                actionVisuals.find { it.actionId == actionId }?.let { visual ->
                    if (visual.line != null && visual.markers.size >= 2) {
                        val sx = visual.markers[0].x + visual.markers[0].width / 2f
                        val sy = visual.markers[0].y + visual.markers[0].height / 2f
                        val ex = visual.markers[1].x + visual.markers[1].width / 2f
                        val ey = visual.markers[1].y + visual.markers[1].height / 2f
                        visual.line.updatePosition(sx, sy, ex, ey)
                    }
                }
            }
        }
    }

    fun recreateVisuals(container: ViewGroup) {
        // Defer execution until the container is laid out to ensure its location is accurate.
        container.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // Remove the listener to avoid this running multiple times
                container.viewTreeObserver.removeOnGlobalLayoutListener(this)

                // Now that the layout is complete, we can safely get the location and create visuals.
                // Guard against recreating visuals if they already exist.
                if (actionVisuals.isNotEmpty()) {
                    Log.d("ACTION_MANAGER", "recreateVisuals: Visuals already exist. Ensuring they are visible in setup mode.")
                    // ** FIX: Do not force setup mode. Only ensure visibility matches state.
                    if (isSetupMode) {
                        startSetupMode()
                    } else {
                        endSetupMode()
                    }
                    return
                }

                Log.d("ACTION_MANAGER", "recreateVisuals: Starting to recreate visuals for ${actions.size} actions.")

                val overlayLocation = IntArray(2)
                container.getLocationOnScreen(overlayLocation)
                val overlayX = overlayLocation[0]
                val overlayY = overlayLocation[1]
                Log.d("ACTION_MANAGER", "recreateVisuals: Overlay container on-screen at ($overlayX, $overlayY)")

                for (action in actions) {
                    val markers = mutableListOf<ActionMarker>()
                    var line: ConnectionLineView? = null

                    action.points.forEachIndexed { index, screenPoint ->
                        // Translate the absolute screen point to a point relative to the overlay container
                        val localPoint = PointF(screenPoint.x - overlayX, screenPoint.y - overlayY)
                        Log.d("ACTION_MANAGER", "Action ID ${action.id}, Point $index: Stored screen coord (${screenPoint.x}, ${screenPoint.y}) -> Calculated local coord (${localPoint.x}, ${localPoint.y})")

                        val label = when {
                            action.type == ActionType.CLICK -> "Click ${action.id}"
                            index == 0 -> "${action.type} Start"
                            else -> "${action.type} End"
                        }

                        val marker =
                            createMarker(
                                container,
                                localPoint,
                                label
                            )
                        marker.setOnClickListener {
                            if (pendingAction == null) {
                                startEditingStep(
                                    container,
                                    action.id,
                                    marker
                                )
                            }
                        }
                        marker.setOnPositionChanged { localX, localY ->
                            val location = IntArray(2)
                            container.getLocationOnScreen(location)
                            val overlayX = location[0]
                            val overlayY = location[1]

                            // localX, localY are already the center of the marker relative to container
                            val centerScreenX = localX + overlayX
                            val centerScreenY = localY + overlayY

                            updateActionPoint(
                                action.id,
                                index,
                                centerScreenX,
                                centerScreenY
                            )
                        }


                        // ** FIX: Set visibility based on current mode, not blindly true.
                        marker.isVisible = isSetupMode
                        markers.add(marker)
                    }

                    if (markers.size > 1) {
                        line = ConnectionLineView(container.context).apply {
                            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                            // ** FIX: Set visibility based on current mode
                            visibility = if (isSetupMode) View.VISIBLE else View.GONE
                        }
                        container.addView(line, 0)

                        line.post {
                            actionVisuals.find { it.actionId == action.id }?.let { visual ->
                                if (visual.line != null && visual.markers.size >= 2) {
                                    val sx = visual.markers[0].x + visual.markers[0].width / 2f
                                    val sy = visual.markers[0].y + visual.markers[0].height / 2f
                                    val ex = visual.markers[1].x + visual.markers[1].width / 2f
                                    val ey = visual.markers[1].y + visual.markers[1].height / 2f
                                    visual.line.updatePosition(sx, sy, ex, ey)
                                    Log.d("ACTION_MANAGER", "Action ID ${action.id}: Line drawn from ($sx, $sy) to ($ex, $ey)")
                                }
                            }
                        }
                    }
                    actionVisuals.add(ActionVisuals(action.id, markers, line))
                }
                Log.d("ACTION_MANAGER", "recreateVisuals: Finished recreating visuals.")
            }
        })
    }

    fun releaseViews(container: ViewGroup) {
        removeConfirmation(container)

        actionVisuals.forEach { visual ->
            visual.markers.forEach { it.removeFromParent() }
            visual.line?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        actionVisuals.clear()

        if (pendingAction != null) {
            cancelPending(container)
        }
    }

    fun getActions(): List<Action> = actions.toList()

    /**
     * Clears only the action data. Does not remove any views from the UI.
     * Use releaseViews() to clear the visuals.
     */
    fun clearAllActions() {
        actions.clear()
        nextId = 1
        actionCountListener?.onActionCountChanged(0)
    }

    fun isConfirmationShowing(container: ViewGroup): Boolean {
        return container.findViewWithTag<View>(CONFIRMATION_VIEW_TAG) != null
    }
}
