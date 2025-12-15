package com.example.automationcompanion.features.gesture_recording_playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.example.automationcompanion.features.gesture_recording_playback.managers.PresetManager
import com.example.automationcompanion.features.gesture_recording_playback.overlay.OverlayService
import com.example.automationcompanion.features.gesture_recording_playback.ui.components.ConfirmDeleteDialog
import com.example.automationcompanion.features.gesture_recording_playback.ui.components.NewPresetDialog
import com.example.automationcompanion.features.gesture_recording_playback.ui.presets.PresetsScreen
import com.example.automationcompanion.features.gesture_recording_playback.utils.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent as AndroidIntent

@Composable
fun GestureRecordingScreen() {
    val context = LocalContext.current
    val permissionHelper = remember { PermissionHelper(context) }

    // Compose-observed preset list
    val presetsState = remember { mutableStateListOf<String>() }
    val coroutineScope = rememberCoroutineScope()

    var showNewDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteFor by remember { mutableStateOf<String?>(null) }

    // Broadcast receiver for preset saved events
    val lbm = LocalBroadcastManager.getInstance(context)

    fun startOverlayIfAllowed(presetName: String) {
        when {
            !permissionHelper.hasOverlayPermission() ->
                permissionHelper.requestOverlayPermission()

            !permissionHelper.isAccessibilityServiceEnabled() ->
                permissionHelper.requestAccessibilityPermission()

            !permissionHelper.hasNotificationPermission() ->
                permissionHelper.requestNotificationPermission()

            else -> {
                val intent = AndroidIntent(context, OverlayService::class.java).apply {
                    putExtra(OverlayService.EXTRA_PRESET_NAME, presetName)
                }
                context.startService(intent)
            }
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == OverlayService.ACTION_PRESET_SAVED) {
                    // reload presets
                    coroutineScope.launch { loadPresets(context, presetsState) }
                }
            }
        }
        lbm.registerReceiver(receiver, IntentFilter(OverlayService.ACTION_PRESET_SAVED))
        onDispose {
            lbm.unregisterReceiver(receiver)
        }
    }

    // initial load
    LaunchedEffect(Unit) {
        loadPresets(context, presetsState)
    }

    PresetsScreen(
        presets = presetsState,
        onAddNewClicked = { showNewDialog = true },
        onPlay = { startOverlayIfAllowed(it)},
        onDelete = { presetName ->
            confirmDeleteFor = presetName
        },
        onItemClicked = { /* optional: navigate to edit screen */ }
    )

    if (showNewDialog) {
        NewPresetDialog(
            onCreate = { newName ->
                val nameTrim = newName.trim()
                if (nameTrim.isNotEmpty()) {
                    PresetManager.savePreset(context, nameTrim, emptyList())
                    coroutineScope.launch { loadPresets(context, presetsState) }
                    startOverlayIfAllowed(nameTrim)
                }
                showNewDialog = false
            },
            onCancel = { showNewDialog = false }
        )
    }

    confirmDeleteFor?.let { presetName ->
        ConfirmDeleteDialog(
            presetName = presetName,
            onConfirm = {
                PresetManager.deletePreset(context, presetName)
                coroutineScope.launch { loadPresets(context, presetsState) }
                confirmDeleteFor = null
            },
            onCancel = { confirmDeleteFor = null }
        )
    }
}

/** Helper: load presets into the compose list (runs IO dispatcher) */
private suspend fun loadPresets(context: Context, stateList: MutableList<String>) {
    withContext(Dispatchers.IO) {
        val list = PresetManager.listPresets(context)
        // switch to main implicitly when updating state in Compose; but to be explicit:
        withContext(Dispatchers.Main) {
            stateList.clear()
            stateList.addAll(list)
        }
    }
}



