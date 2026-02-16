package com.autonion.automationcompanion.features.gesture_recording_playback.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.autonion.automationcompanion.R
import com.autonion.automationcompanion.databinding.OverlayViewBinding
import com.autonion.automationcompanion.features.gesture_recording_playback.managers.ActionManager
import com.autonion.automationcompanion.features.gesture_recording_playback.managers.PresetManager
import com.autonion.automationcompanion.features.gesture_recording_playback.managers.SettingsManager

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var binding: OverlayViewBinding

    // Window 1: Fullscreen Marker Area
    private lateinit var markersView: FrameLayout
    private lateinit var markersParams: WindowManager.LayoutParams

    // Window 2: Floating Control Panel
    private lateinit var controlsView: View
    private lateinit var controlsParams: WindowManager.LayoutParams

    // Dialog params preservation
    private var preDialogParams: WindowManager.LayoutParams? = null

    private var currentPresetName: String? = null

    // Automation state
    private var isPlaying = false
    private var currentLoopCount = 1
    private var isSetupMode = false
    private var isCompactMode = false

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STOP -> stopPlaying()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentPresetName = intent?.getStringExtra(EXTRA_PRESET_NAME)
        currentPresetName?.let {
            val actions = PresetManager.loadPreset(this, it)
            if (::markersView.isInitialized) {
                ActionManager.releaseViews(markersView)
                ActionManager.loadActions(actions)
                // Use a posted runnable to wait for layout if needed, though GlobalLayoutListener handles init
                if (markersView.isAttachedToWindow) {
                    ActionManager.recreateVisuals(markersView)
                }
            } else {
                ActionManager.loadActions(actions)
            }
            updateActionCount()
        }
        updateSaveButtonState()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        
        // Load settings
        currentLoopCount = SettingsManager.getLoopCount(this)
        isCompactMode = SettingsManager.isCompactMode(this)
        
        setupOverlay()
//        startForeground(1, NotificationHelper.createNotification(this))

        // Start foreground service with notification
        startForegroundServiceWithNotification()

        // Register broadcast receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            playbackReceiver,
            IntentFilter(ACTION_STOP)
        )

        setFocusListener()
    }

    private fun setFocusListener() {
        ActionManager.setFocusListener(object : ActionManager.FocusListener {
            override fun onFocusRequired() {
                updateFocusAndSoftInput(true)
            }

            override fun onFocusReleased() {
                updateFocusAndSoftInput(false)
            }

            override fun onRequestFocus(view: View) {
                view.post {
                    view.requestFocus()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service for automation overlay"
            }

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Automation Companion")
            .setContentText("Overlay is running")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }


    private fun setupOverlay() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        binding = OverlayViewBinding.inflate(inflater)

        // --- 1. Setup Markers Window (Fullscreen, Background) ---
        markersView = FrameLayout(this)
        markersParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            // Initial State: Interact Mode -> NOT_TOUCHABLE (Pass-through)
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.START or Gravity.TOP
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            x = 0
            y = 0
        }
        windowManager.addView(markersView, markersParams)

        // Ensure markers are drawn once layout is ready
        markersView.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    markersView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    ActionManager.recreateVisuals(markersView)
                }
            }
        )

        // --- 2. Setup Controls Window (Wrap Content, Foreground) ---
        // Use binding.root as the container. It wraps the Control Panel and handles margins.
        controlsView = binding.root

        controlsParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            // Always touchable, never focusable (unless we need input)
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.START or Gravity.TOP
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = 0
            y = 100
        }
        windowManager.addView(controlsView, controlsParams)

        setupControls()
    }

    private fun setupControls() {
        // Set up action count listener
        ActionManager.setActionCountListener(object : ActionManager.ActionCountListener {
            override fun onActionCountChanged(newCount: Int) {
                updateActionCount()
            }
        })
        //Main Controls
        binding.btnToggleInput.setOnClickListener {
            isSetupMode = !isSetupMode
            if (isSetupMode) {
                enableSetupMode()
            } else {
                disableSetupMode()
            }
        }

        // Touch listener moves the CONTROLS window (controlsView is the parent of controlPanel)
        // We attach the listener to the PANEL so dragging the panel moves the whole window.
        // We update 'controlsParams' which applies to 'controlsView' (binding.root).
        binding.controlPanel.setOnTouchListener(OverlayTouchListener(windowManager, controlsView, controlsParams))

        binding.btnAdd.setOnClickListener {
            binding.mainControls.visibility = View.GONE
            binding.addControls.visibility = View.VISIBLE
            updateControlLayout()
        }

        binding.btnBack.setOnClickListener {
            binding.addControls.visibility = View.GONE
            binding.mainControls.visibility = View.VISIBLE
            updateControlLayout()
        }

        binding.btnPlay.setOnClickListener {
            if (ActionManager.getActions().isNotEmpty() && !ActionManager.isConfirmationShowing(markersView)) {
                startPlaying()
                broadcastIntent(ACTION_PLAY)
            }
        }

        binding.btnStop.setOnClickListener {
            stopPlaying()
            broadcastIntent(ACTION_STOP)
        }

        binding.btnRestart.setOnClickListener {
            stopPlaying()
            broadcastIntent(ACTION_STOP)
            binding.root.postDelayed({
                startPlaying()
                broadcastIntent(ACTION_PLAY)
            }, 300)
        }

        binding.btnSave.setOnClickListener {
            if (ActionManager.isConfirmationShowing(markersView)) {
                Toast.makeText(this, "Please confirm or cancel the pending action first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            currentPresetName?.let {
                try {
                    PresetManager.savePreset(this, it, ActionManager.getActions())
                    showStatusAnimation(true)
                    broadcastIntent(ACTION_PRESET_SAVED)
                } catch (e: Exception) {
                    showStatusAnimation(false)
                    android.widget.Toast.makeText(this, "Error saving: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } ?: android.widget.Toast.makeText(this, "No preset selected", android.widget.Toast.LENGTH_SHORT).show()
        }

        binding.btnClear.setOnClickListener {
            ActionManager.releaseViews(markersView)
            ActionManager.clearAllActions()
            updateActionCount()
            broadcastIntent(ACTION_CLEARED)
        }

        binding.btnClose.setOnClickListener {
            stopSelf()
        }

        // When adding an action, we MUST enable setup mode (touchable markers window)
        val onAddAction = {
            if (!isSetupMode) {
                isSetupMode = true
                enableSetupMode()
            }
            binding.addControls.visibility = View.GONE
            binding.mainControls.visibility = View.VISIBLE
            updateControlLayout()
        }

        binding.btnAddClick.setOnClickListener {
            ActionManager.addNewClick(markersView)
            onAddAction()
            broadcastIntent(ACTION_CLICK_ADDED)
        }


        binding.btnAddSwipe.setOnClickListener {
            ActionManager.addNewSwipe(markersView)
            onAddAction()
            broadcastIntent(ACTION_SWIPE_ADDED)
        }

        binding.btnAddLongClick.setOnClickListener {
            ActionManager.addNewLongClick(markersView)
            onAddAction()
            broadcastIntent(ACTION_LONG_CLICK_ADDED)
        }

        updateActionCount()
        updateLoopCountDisplay()
        updateSaveButtonState()

        binding.layoutLoopCount.setOnClickListener {
            showLoopCountDialog()
        }
        
        binding.btnToggleLayout.setOnClickListener {
            isCompactMode = !isCompactMode
            SettingsManager.saveCompactMode(this, isCompactMode)
            updateCompactMode()
        }
        
        // Apply initial state
        updateCompactMode()
    }

    private fun updateCompactMode() {
        val visibility = if (isCompactMode) View.GONE else View.VISIBLE
        // 1. Force Vertical Orientation (User request: "Also make it vertical")
        val orientation = LinearLayout.VERTICAL
        binding.mainControls.orientation = orientation
        binding.addControls.orientation = orientation
        
        // 2. Stats/Loop are VISIBLE (Short form in compact)
        binding.tvActionCount.visibility = View.VISIBLE
        binding.layoutLoopCount.visibility = View.VISIBLE

        // 3. Update Background (Pill shape for compact)
        if (isCompactMode) {
            binding.controlPanel.setBackgroundResource(R.drawable.rounded_panel_dark)
            // Reduce vertical footprint
            binding.layoutLoopCount.minimumHeight = 0
            binding.layoutLoopCount.setPadding(0, dpToPx(2), 0, dpToPx(2)) // Minimal padding
        } else {
            // Revert to default rectangular-ish background color
            binding.controlPanel.setBackgroundColor(android.graphics.Color.parseColor("#CC1C1C1C"))
            binding.layoutLoopCount.minimumHeight = dpToPx(48)
            binding.layoutLoopCount.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }
        
        // Hide divider in Add Controls in Compact Mode for cleaner look
        if (binding.addControls.childCount > 3) {
             binding.addControls.getChildAt(3).visibility = visibility
        }

        // List of buttons (LinearLayouts)
        val buttons = listOf(
            binding.btnPlay, binding.btnStop, binding.btnRestart, 
            binding.btnToggleInput, binding.btnAdd, binding.btnSave, 
            binding.btnClear, binding.btnClose,
            binding.btnAddClick, binding.btnAddLongClick, binding.btnAddSwipe, 
            binding.btnBack, binding.btnToggleLayout
        )
        
        val marginCompact = dpToPx(2) // Tight vertical spacing
        val marginFull = dpToPx(6)    // Standard vertical spacing

        buttons.forEach { layout ->
            // Toggle Text (Index 1)
            // ... existing loop body ...
            if (layout.childCount > 1) {
                layout.getChildAt(1).visibility = visibility
            }
            
            // Update Styling
            val params = layout.layoutParams as android.widget.LinearLayout.LayoutParams
            if (isCompactMode) {
                // Transparent background, tight margins
                layout.setBackgroundResource(R.drawable.btn_overlay_transparent_ripple)
                params.width = android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                params.setMargins(0, marginCompact, 0, marginCompact)
            } else {
                // Original background, standard margins
                layout.setBackgroundResource(R.drawable.btn_overlay_primary_ripple)
                params.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                params.setMargins(0, marginFull, 0, marginFull)
            }
            layout.layoutParams = params
        }

        // Specific handling for Toggle button icon
        binding.tvToggleLayout.visibility = visibility
        if (isCompactMode) {
            binding.ivToggleLayout.setImageResource(R.drawable.ic_view_full)
        } else {
            binding.ivToggleLayout.setImageResource(R.drawable.ic_view_compact)
        }
        
        // Refresh Stats Display
        updateActionCount()
        updateLoopCountDisplay()
        
        updateControlLayout()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    private fun showLoopCountDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.loop_input_dialog, markersView, false)
        val etLoopCount = view.findViewById<EditText>(R.id.etLoopCount)
        val cbInfinite = view.findViewById<CheckBox>(R.id.cbInfinite)
        val btnSave = view.findViewById<Button>(R.id.btnSave)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)

        val isInfinite = currentLoopCount <= 0
        etLoopCount.setText(if (isInfinite) "1" else currentLoopCount.toString())
        cbInfinite.isChecked = isInfinite
        etLoopCount.isEnabled = !isInfinite

        cbInfinite.setOnCheckedChangeListener { _, isChecked ->
            etLoopCount.isEnabled = !isChecked
        }

        // For the dialog, we need the MARKERS window to be focusable/touchable
        preDialogParams = WindowManager.LayoutParams().apply {
            copyFrom(markersParams)
        }

        markersParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        markersParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        windowManager.updateViewLayout(markersView, markersParams)

        markersView.addView(view)

        val params = view.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.CENTER
        view.layoutParams = params

        controlsView.visibility = View.GONE

        if (!isInfinite) {
            etLoopCount.post {
                etLoopCount.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(etLoopCount, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        btnSave.setOnClickListener {
            currentLoopCount = if (cbInfinite.isChecked) 0 else etLoopCount.text.toString().toIntOrNull() ?: 1
            SettingsManager.saveLoopCount(this, currentLoopCount)
            updateLoopCountDisplay()

            val intent = Intent(ACTION_LOOP_COUNT_CHANGED)
            intent.putExtra(EXTRA_LOOP_COUNT, currentLoopCount)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

            closeLoopDialog(view)
        }

        btnCancel.setOnClickListener {
            closeLoopDialog(view)
        }
    }


    private fun closeLoopDialog(view: View) {
        val etLoopCount = view.findViewById<EditText>(R.id.etLoopCount)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etLoopCount.windowToken, 0)

        markersView.removeView(view)
        controlsView.visibility = View.VISIBLE

        if (preDialogParams != null) {
            // Restore previous markers window state
            markersParams.copyFrom(preDialogParams)
            // Ensure width/height match parent as copyFrom might copy explicit values if they changed
            markersParams.width = WindowManager.LayoutParams.MATCH_PARENT
            markersParams.height = WindowManager.LayoutParams.MATCH_PARENT
            windowManager.updateViewLayout(markersView, markersParams)
            preDialogParams = null
        }
    }

    private fun startPlaying() {
        isPlaying = true

        // Show Playback controls
        binding.btnStop.visibility = View.VISIBLE
        binding.btnRestart.visibility = View.VISIBLE

        // Hide Setup/Main controls
        binding.btnPlay.visibility = View.GONE
        binding.btnSave.visibility = View.GONE
        binding.btnToggleInput.visibility = View.GONE
        binding.btnAdd.visibility = View.GONE
        binding.btnClear.visibility = View.GONE
        binding.btnClose.visibility = View.GONE

        // Hide Info/Stats
        binding.tvActionCount.visibility = View.GONE
        binding.layoutLoopCount.visibility = View.GONE

        // Post the layout update to ensure view visibility changes have propagated
        binding.root.post {
            updateControlLayout()
        }

        // Send current loop count before playing
        val intent = Intent(ACTION_LOOP_COUNT_CHANGED)
        intent.putExtra(EXTRA_LOOP_COUNT, currentLoopCount)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        if (isSetupMode) {
            disableSetupMode()
        }
        ActionManager.endSetupMode()
    }

    private fun stopPlaying() {
        // Ensure we are on main thread if called from receiver
        binding.root.post {
            isPlaying = false

            // Hide Playback controls
            binding.btnStop.visibility = View.GONE
            binding.btnRestart.visibility = View.GONE

            // Restore Setup/Main controls
            binding.btnPlay.visibility = View.VISIBLE
            binding.btnSave.visibility = View.VISIBLE
            binding.btnToggleInput.visibility = View.VISIBLE
            binding.btnAdd.visibility = View.VISIBLE
            binding.btnClear.visibility = View.VISIBLE
            binding.btnClose.visibility = View.VISIBLE

            // Restore Info/Stats
            binding.tvActionCount.visibility = View.VISIBLE
            binding.layoutLoopCount.visibility = View.VISIBLE
            updateControlLayout()

            // Re-enable in case they were disabled (legacy code cleanup)
//            binding.btnAdd.isEnabled = true
//            binding.btnClear.isEnabled = true
//            binding.btnToggleInput.isEnabled = true
        }
    }

    private fun updateControlLayout() {
        if (::controlsView.isInitialized && ::controlsParams.isInitialized) {
            // Force re-layout by updating the view layout with current params
            windowManager.updateViewLayout(controlsView, controlsParams)
        }
    }

    private fun updateSaveButtonState() {
        binding.btnSave.isEnabled = !currentPresetName.isNullOrEmpty()
    }

    private fun updateToggleState(isEditing: Boolean) {
        if (isEditing) {
            binding.tvToggleLabel.setText(R.string.mode_interact)
            binding.ivToggleIcon.setImageResource(R.drawable.ic_touch_app)
        } else {
            binding.tvToggleLabel.setText(R.string.mode_edit)
            binding.ivToggleIcon.setImageResource(R.drawable.ic_edit)
        }
    }


    private fun enableSetupMode() {
        ActionManager.startSetupMode()
        // Make markers window touchable
        markersParams.flags = markersParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        windowManager.updateViewLayout(markersView, markersParams)
        updateToggleState(true)
    }

    private fun disableSetupMode() {
        ActionManager.endSetupMode()
        // Make markers window NOT touchable (pass-through)
        markersParams.flags = markersParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windowManager.updateViewLayout(markersView, markersParams)
        updateToggleState(false)
    }

    private fun updateActionCount() {
        val count = ActionManager.getActions().size
        if (isCompactMode) {
            binding.tvActionCount.text = "$count"
            binding.tvActionCount.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stat_action, 0, 0, 0)
            binding.tvActionCount.compoundDrawablePadding = dpToPx(4)
        } else {
            binding.tvActionCount.text = "Actions: $count"
            binding.tvActionCount.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun updateLoopCountDisplay() {
        val countText = if (currentLoopCount <= 0) "∞" else "$currentLoopCount"
        
        if (isCompactMode) {
            binding.tvLoopCount.text = countText
            binding.tvLoopCount.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_stat_loop, 0, 0, 0)
            binding.tvLoopCount.compoundDrawablePadding = dpToPx(4)
            // Hide edit icon (index 1)
            if (binding.layoutLoopCount.childCount > 1) {
                binding.layoutLoopCount.getChildAt(1).visibility = View.GONE
            }
        } else {
            val fullText = if (currentLoopCount <= 0) "Loop: ∞" else "Loop: $currentLoopCount"
            binding.tvLoopCount.text = fullText
            binding.tvLoopCount.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            // Show edit icon
            if (binding.layoutLoopCount.childCount > 1) {
                binding.layoutLoopCount.getChildAt(1).visibility = View.VISIBLE
            }
        }
    }

    private fun showStatusAnimation(isSuccess: Boolean) {
        val icon = if (isSuccess) R.drawable.ic_success else R.drawable.ic_error
        binding.ivSaveStatus.setImageResource(icon)
        binding.ivSaveStatus.visibility = View.VISIBLE
        binding.ivSaveStatus.alpha = 0f
        binding.ivSaveStatus.scaleX = 0f
        binding.ivSaveStatus.scaleY = 0f
        
        binding.ivSaveStatus.animate().cancel()

        binding.ivSaveStatus.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setStartDelay(0)
            .setInterpolator(android.view.animation.OvershootInterpolator(2f))
            .withEndAction {
                 binding.ivSaveStatus.animate()
                     .alpha(0f)
                     .scaleX(0.5f)
                     .scaleY(0.5f)
                     .setStartDelay(1500)
                     .setDuration(300)
                     .setInterpolator(android.view.animation.AccelerateInterpolator())
                     .withEndAction {
                         binding.ivSaveStatus.visibility = View.GONE
                     }
                     .start()
            }
            .start()
    }


    private fun broadcastIntent(action: String) {
        val intent = Intent(action)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackReceiver)

        if (::markersView.isInitialized) {
            ActionManager.releaseViews(markersView)
            windowManager.removeView(markersView)
        }
        if (::controlsView.isInitialized) {
            windowManager.removeView(controlsView)
        }
    }

    private fun updateFocusAndSoftInput(isFocusable: Boolean) {
        // Focus usually needed for markers window (dialogs/edits)
        if (isFocusable) {
            markersParams.flags = markersParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            markersParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            markersParams.flags = markersParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            markersParams.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        windowManager.updateViewLayout(markersView, markersParams)
    }

    companion object {

        const val EXTRA_PRESET_NAME = "extra_preset_name"
        private const val NOTIFICATION_CHANNEL_ID = "overlay_service_channel"
        private const val NOTIFICATION_ID = 1234

        const val ACTION_SETUP_STARTED = "SETUP_STARTED"
        const val ACTION_CLICK_ADDED = "CLICK_ADDED"
        const val ACTION_SWIPE_ADDED = "SWIPE_ADDED"
        const val ACTION_LONG_CLICK_ADDED = "LONG_CLICK_ADDED"
        const val ACTION_PLAY = "PLAY"
        const val ACTION_STOP = "STOP"
        const val ACTION_CLEARED = "CLEARED"
        const val ACTION_LOOP_COUNT_CHANGED = "LOOP_COUNT_CHANGED"
        const val EXTRA_LOOP_COUNT = "extra_loop_count"
        const val ACTION_PRESET_SAVED = "PRESET_SAVED"
    }
}