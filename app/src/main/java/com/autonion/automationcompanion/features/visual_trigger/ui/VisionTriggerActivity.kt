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
import android.os.Build
import android.text.TextUtils

class VisionTriggerActivity : ComponentActivity() {

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // After accessibility settings, re-check ALL permissions
    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isAccessibilityEnabled()) {
            // Re-run the full permission chain — don't skip overlay check
            continuePermissionChain()
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
                        pendingRunPresetId = null
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

    // ── Permission chain ────────────────────────────────────────────

    private fun checkPermissionsAndRun(presetId: String) {
        pendingRunPresetId = presetId
        checkAllPermissions()
    }

    private fun checkPermissionsAndStart() {
        pendingRunPresetId = null
        checkAllPermissions()
    }

    /** Single entry point: checks accessibility → overlay → media projection in order */
    private fun checkAllPermissions() {
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

        // All prerequisites met → request media projection
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    /** Called after returning from accessibility settings */
    private fun continuePermissionChain() {
        checkAllPermissions()
    }

    override fun onResume() {
        super.onResume()
        // Re-check overlay permission after returning from settings
        // (overlay settings don't use a result launcher)
    }

    // ── Service launchers ───────────────────────────────────────────

    private fun startExecutionService(resultCode: Int, data: Intent, presetId: String) {
        val serviceIntent = Intent(this, VisionExecutionService::class.java).apply {
            putExtra("EXTRA_RESULT_CODE", resultCode)
            putExtra("EXTRA_RESULT_DATA", data)
            putExtra("EXTRA_PRESET_ID", presetId)
            action = "ACTION_START_EXECUTION"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        }
        // Don't finish() — keep activity alive so user returns here after overlay closes
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        }
        // Don't finish() — keep activity alive so user returns here after overlay closes
    }

    // ── Helpers ─────────────────────────────────────────────────────

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
