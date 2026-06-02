package com.autonion.automationcompanion.features.visual_trigger.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.gesture_recording_playback.overlay.AutomationService
import com.autonion.automationcompanion.features.visual_trigger.service.CaptureOverlayService
import com.autonion.automationcompanion.features.visual_trigger.service.VisionExecutionService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Screenshot
import com.autonion.automationcompanion.ui.components.YouTubeTutorials
import com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionDisclosureDialog
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.ui.components.FeatureTipSheet

@Composable
fun VisualTriggerRoute(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    var pendingRunPresetId by remember { mutableStateOf<String?>(null) }
    var pendingPresetName by remember { mutableStateOf("New Automation") }

    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    var showOverlayDisclosure by remember { mutableStateOf(false) }
    var showMediaProjectionDisclosure by remember { mutableStateOf(false) }

    // ── First-visit Feature Tip ──
    val onboardingPrefs = remember { OnboardingPreferences.getInstance(context) }
    var showTip by remember { mutableStateOf(!onboardingPrefs.hasTipBeenSeen("visual_trigger")) }

    if (showTip) {
        FeatureTipSheet(
            title = "Visual Trigger",
            tips = listOf(
                "Capture a screenshot, then **draw a selection box** around the target element",
                "Set the **action** to run when the image is detected on screen",
                "Requires **Accessibility** and **Screen Capture** permissions"
            ),
            icon = Icons.Default.Screenshot,
            iconColor = androidx.compose.ui.graphics.Color(0xFF00E676),
            youtubeLink = YouTubeTutorials.VISUAL_TRIGGER,
            onDismiss = { onboardingPrefs.markTipSeen("visual_trigger"); showTip = false }
        )
    }

    fun isAccessibilityEnabled(): Boolean {
        if (AccessibilityRouter.isServiceConnected()) return true
        val expectedComponentName = ComponentName(context, AutomationService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) return true
        }
        return false
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK && result.data != null) {
            val intentData = result.data!!
            if (pendingRunPresetId != null) {
                val serviceIntent = Intent(context, VisionExecutionService::class.java).apply {
                    putExtra("EXTRA_RESULT_CODE", result.resultCode)
                    putExtra("EXTRA_RESULT_DATA", intentData)
                    putExtra("EXTRA_PRESET_ID", pendingRunPresetId)
                    action = "ACTION_START_EXECUTION"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                }
                pendingRunPresetId = null
            } else {
                val serviceIntent = Intent(context, CaptureOverlayService::class.java).apply {
                    putExtra("EXTRA_RESULT_CODE", result.resultCode)
                    putExtra("EXTRA_RESULT_DATA", intentData)
                    putExtra("EXTRA_PRESET_NAME", pendingPresetName)
                    action = "ACTION_START_OVERLAY"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                }
            }
        } else {
            Toast.makeText(context, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            pendingRunPresetId = null
        }
    }

    val accessibilityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (isAccessibilityEnabled()) {
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "Please enable Display Over Other Apps", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } else {
                showMediaProjectionDisclosure = true
            }
        } else {
            Toast.makeText(context, "Accessibility is required", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkAllPermissions() {
        if (!isAccessibilityEnabled()) {
            showAccessibilityDisclosure = true
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            showOverlayDisclosure = true
            return
        }
        showMediaProjectionDisclosure = true
    }

    PermissionDisclosureDialog(
        showDialog = showAccessibilityDisclosure,
        title = "Accessibility Service Required",
        description = "Autonion needs Accessibility Service to automate screen interactions and taps for Visual Triggers. Please enable it in the next screen.",
        icon = Icons.Default.Accessibility,
        onDismiss = { showAccessibilityDisclosure = false },
        onContinue = {
            showAccessibilityDisclosure = false
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    )

    PermissionDisclosureDialog(
        showDialog = showOverlayDisclosure,
        title = "Display Over Other Apps Required",
        description = "Autonion needs to display over other apps to capture the screen and show the Visual Trigger UI.",
        icon = Icons.Default.Layers,
        onDismiss = { showOverlayDisclosure = false },
        onContinue = {
            showOverlayDisclosure = false
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    )

    PermissionDisclosureDialog(
        showDialog = showMediaProjectionDisclosure,
        title = "Screen Capture Required",
        description = "Autonion needs to capture your screen to detect visual elements and triggers. The screen content is processed locally on your device and captured images may be stored on-device for editing. They are not shared.",
        icon = Icons.Default.Screenshot,
        onDismiss = { showMediaProjectionDisclosure = false },
        onContinue = {
            showMediaProjectionDisclosure = false
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    )

    VisionTriggerScreen(
        onAddClicked = { name ->
            pendingPresetName = name
            pendingRunPresetId = null
            checkAllPermissions()
        },
        onEditPreset = { presetId ->
            val intent = Intent(context, VisionEditorActivity::class.java).apply {
                putExtra("PRESET_ID", presetId)
            }
            context.startActivity(intent)
        },
        onRunPreset = { presetId ->
            pendingRunPresetId = presetId
            checkAllPermissions()
        },
        onBack = onBack
    )
}
