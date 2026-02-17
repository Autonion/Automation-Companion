package com.autonion.automationcompanion.features.visual_trigger.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.autonion.automationcompanion.ui.theme.AppTheme
import com.autonion.automationcompanion.features.visual_trigger.service.CaptureOverlayService
import com.autonion.automationcompanion.features.visual_trigger.service.VisionExecutionService
import com.autonion.automationcompanion.AccessibilityRouter
import com.autonion.automationcompanion.features.gesture_recording_playback.overlay.AutomationService
import android.content.ComponentName
import android.text.TextUtils

class VisionTriggerActivity : ComponentActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isAccessibilityEnabled()) {
            startMediaProjectionFlow()
        } else {
            Toast.makeText(this, "Accessibility is required", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingRunPresetId: String? = null
    private var pendingPresetName: String = "New Automation"

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            if (pendingRunPresetId != null) {
                startExecutionService(result.resultCode, result.data!!, pendingRunPresetId!!)
                pendingRunPresetId = null
            } else {
                startCaptureOverlay(result.resultCode, result.data!!)
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            pendingRunPresetId = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                VisionTriggerScreen(
                    onAddClicked = { name ->
                        pendingPresetName = name
                        checkPermissionsAndStart()
                    },
                    onEditPreset = { presetId ->
                        openEditorForPreset(presetId)
                    },
                    onRunPreset = { presetId ->
                        checkPermissionsAndRun(presetId)
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun openEditorForPreset(presetId: String) {
        val intent = Intent(this, VisionEditorActivity::class.java).apply {
            putExtra("PRESET_ID", presetId)
        }
        startActivity(intent)
    }

    private fun checkPermissionsAndRun(presetId: String) {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service", Toast.LENGTH_LONG).show()
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        pendingRunPresetId = presetId
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun checkPermissionsAndStart() {
        pendingRunPresetId = null
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "Please enable Accessibility Service", Toast.LENGTH_LONG).show()
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please enable Display Over Other Apps", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            return
        }

        startMediaProjectionFlow()
    }

    private fun startExecutionService(resultCode: Int, data: Intent, presetId: String) {
        val serviceIntent = Intent(this, VisionExecutionService::class.java).apply {
            putExtra("EXTRA_RESULT_CODE", resultCode)
            putExtra("EXTRA_RESULT_DATA", data)
            putExtra("EXTRA_PRESET_ID", presetId)
            action = "ACTION_START_EXECUTION"
        }
        startForegroundService(serviceIntent)
        finish()
    }

    private fun startMediaProjectionFlow() {
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureOverlay(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
            putExtra("EXTRA_RESULT_CODE", resultCode)
            putExtra("EXTRA_RESULT_DATA", data)
            putExtra("EXTRA_PRESET_NAME", pendingPresetName)
            action = "ACTION_START_OVERLAY"
        }
        startForegroundService(serviceIntent)
        finish()
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (AccessibilityRouter.isServiceConnected()) return true

        val expectedComponentName = ComponentName(this, AutomationService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
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
}
