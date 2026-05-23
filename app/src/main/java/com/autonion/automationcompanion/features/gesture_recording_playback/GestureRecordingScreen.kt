package com.autonion.automationcompanion.features.gesture_recording_playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.features.gesture_recording_playback.managers.PresetManager
import com.autonion.automationcompanion.features.gesture_recording_playback.overlay.OverlayService
import com.autonion.automationcompanion.features.gesture_recording_playback.ui.components.ConfirmDeleteDialog
import com.autonion.automationcompanion.features.gesture_recording_playback.ui.components.NewPresetDialog
import com.autonion.automationcompanion.features.gesture_recording_playback.ui.presets.PresetsScreen
import com.autonion.automationcompanion.features.gesture_recording_playback.utils.PermissionHelper
import com.autonion.automationcompanion.ui.components.FeatureTipSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Layers
import android.content.Intent as AndroidIntent

@Composable
fun GestureRecordingScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val permissionHelper = remember { PermissionHelper(context) }

    // ── First-visit Feature Tip ──
    val onboardingPrefs = remember { OnboardingPreferences.getInstance(context) }
    var showTip by remember { mutableStateOf(!onboardingPrefs.hasTipBeenSeen("gesture_recording")) }

    if (showTip) {
        FeatureTipSheet(
            title = "Gesture Recording",
            tips = listOf(
                "Tap **+ New Preset** to start recording a new gesture sequence",
                "**Long-press** any action marker to edit its delay, duration, or type",
                "Use the **play button** to replay a recorded gesture preset"
            ),
            icon = Icons.Default.TouchApp,
            iconColor = Color(0xFF7C4DFF),
            youtubeLink = null,
            onDismiss = { onboardingPrefs.markTipSeen("gesture_recording"); showTip = false }
        )
    }

    // Compose-observed preset list
    val presetsState = remember { mutableStateListOf<String>() }
    val coroutineScope = rememberCoroutineScope()

    var showNewDialog by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteFor by remember { mutableStateOf<String?>(null) }
    
    var showOverlayDisclosure by remember { mutableStateOf(false) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var pendingPresetName by remember { mutableStateOf<String?>(null) }

    // Broadcast receiver for preset saved events
    val lbm = LocalBroadcastManager.getInstance(context)

    fun startOverlayIfAllowed(presetName: String) {
        when {
            !permissionHelper.hasOverlayPermission() -> {
                pendingPresetName = presetName
                showOverlayDisclosure = true
            }

            !permissionHelper.isAccessibilityServiceEnabled() -> {
                pendingPresetName = presetName
                showAccessibilityDisclosure = true
            }

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
        onBack = onBack,
        onAddNewClicked = { showNewDialog = true },
        onPlay = { startOverlayIfAllowed(it) },
        onDelete = { presetName -> confirmDeleteFor = presetName },
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
                // Stop the overlay service if it's running for this preset
                context.stopService(AndroidIntent(context, OverlayService::class.java))
                coroutineScope.launch { loadPresets(context, presetsState) }
                confirmDeleteFor = null
            },
            onCancel = { confirmDeleteFor = null }
        )
    }

    if (showOverlayDisclosure) {
        com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionDisclosureDialog(
            showDialog = showOverlayDisclosure,
            onDismiss = { 
                showOverlayDisclosure = false
                pendingPresetName = null
            },
            onContinue = {
                showOverlayDisclosure = false
                permissionHelper.requestOverlayPermission()
            },
            title = "Display Over Other Apps Required",
            description = "Autonion requires the 'Display over other apps' permission to show the gesture recording controls on top of other applications. This allows you to record and playback gestures anywhere on your screen.",
            icon = Icons.Rounded.Layers
        )
    }

    if (showAccessibilityDisclosure) {
        com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionDisclosureDialog(
            showDialog = showAccessibilityDisclosure,
            onDismiss = { 
                showAccessibilityDisclosure = false 
                pendingPresetName = null
            },
            onContinue = {
                showAccessibilityDisclosure = false
                permissionHelper.requestAccessibilityPermission()
            },
            title = "Accessibility Service Required",
            description = "Autonion uses the Accessibility Service to simulate touch gestures and clicks during playback, and to record your inputs. We do not use this to collect personal data or observe your typing.",
            icon = Icons.Rounded.AccessibilityNew
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



