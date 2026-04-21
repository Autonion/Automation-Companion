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
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        } else {
            Toast.makeText(context, "Accessibility is required", Toast.LENGTH_SHORT).show()
        }
    }

    fun checkAllPermissions() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(context, "Please enable Accessibility Service", Toast.LENGTH_LONG).show()
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Please enable Display Over Other Apps", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            return
        }
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

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
