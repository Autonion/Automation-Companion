package com.example.automationcompanion.features.gesture_recording_playback.overlay

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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.automationcompanion.R
import com.example.automationcompanion.databinding.OverlayViewBinding
import com.example.automationcompanion.features.gesture_recording_playback.managers.ActionManager
import com.example.automationcompanion.features.gesture_recording_playback.managers.PresetManager
import com.example.automationcompanion.features.gesture_recording_playback.managers.SettingsManager

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
        setupOverlay()
//        startForeground(1, NotificationHelper.createNotification(this))

        // Start foreground service with notification
        startForegroundServiceWithNotification()

        // Register broadcast receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            playbackReceiver,
            IntentFilter(ACTION_STOP)
        )

        // Load settings
        currentLoopCount = SettingsManager.getLoopCount(this)
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
            .setContentTitle("My Automation")
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
                PresetManager.savePreset(this, it, ActionManager.getActions())
                // Show toast inside controls view
                binding.tvSaveConfirmation.visibility = View.VISIBLE
                binding.root.postDelayed({
                    binding.tvSaveConfirmation.visibility = View.GONE
                }, 2000)
                broadcastIntent(ACTION_PRESET_SAVED)
            } ?: Toast.makeText(this, "No preset selected", Toast.LENGTH_SHORT).show()
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
            binding.btnToggleInput.setText(R.string.mode_interact)
        } else {
            binding.btnToggleInput.setText(R.string.mode_edit)
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
        binding.tvActionCount.text = "Actions: $count"
    }

    private fun updateLoopCountDisplay() {
        val text = if (currentLoopCount <= 0) "Loop: ∞" else "Loop: $currentLoopCount"
        binding.tvLoopCount.text = text
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