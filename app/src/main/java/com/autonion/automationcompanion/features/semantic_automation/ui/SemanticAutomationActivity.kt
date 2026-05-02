package com.autonion.automationcompanion.features.semantic_automation.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationService
import com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionDisclosureDialog
import com.autonion.automationcompanion.ui.theme.AppTheme

/**
 * Transparent permission-flow Activity.
 *
 * The Compose UI (SemanticAutomationScreen) passes the command via intent extra.
 * This Activity walks through the permission chain:
 *   Accessibility → Overlay → MediaProjection
 * then starts [SemanticAutomationService] and finishes itself.
 */
class SemanticAutomationActivity : ComponentActivity() {

    companion object {
        private const val MEDIA_PROJECTION_REQUEST = 200
        private const val OVERLAY_PERMISSION_REQUEST = 201
        private const val ACCESSIBILITY_PERMISSION_REQUEST = 202
    }

    private var pendingCommand: String = ""

    private var showAccessibilityDisclosure by mutableStateOf(false)
    private var showOverlayDisclosure by mutableStateOf(false)
    private var showMediaProjectionDisclosure by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCommand = intent.getStringExtra("command") ?: ""
        if (pendingCommand.isBlank()) {
            Toast.makeText(this, "No command provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AppTheme {
                PermissionDisclosureDialog(
                    showDialog = showAccessibilityDisclosure,
                    title = "Accessibility Service Required",
                    description = "Autonion needs Accessibility Service to execute semantic automation commands and interact with on-screen elements. Please enable it in the next screen.",
                    icon = Icons.Default.Accessibility,
                    onDismiss = {
                        showAccessibilityDisclosure = false
                        finish()
                    },
                    onContinue = {
                        showAccessibilityDisclosure = false
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), ACCESSIBILITY_PERMISSION_REQUEST)
                    }
                )

                PermissionDisclosureDialog(
                    showDialog = showOverlayDisclosure,
                    title = "Display Over Other Apps Required",
                    description = "Autonion needs to display over other apps to show the semantic automation overlay UI.",
                    icon = Icons.Default.Layers,
                    onDismiss = {
                        showOverlayDisclosure = false
                        finish()
                    },
                    onContinue = {
                        showOverlayDisclosure = false
                        @Suppress("DEPRECATION")
                        startActivityForResult(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                            OVERLAY_PERMISSION_REQUEST
                        )
                    }
                )

                PermissionDisclosureDialog(
                    showDialog = showMediaProjectionDisclosure,
                    title = "Screen Capture Required",
                    description = "Autonion needs to capture your screen to understand the current UI and execute semantic automation commands. The screen content may be sent to your connected LLM for analysis and is not stored or shared externally.",
                    icon = Icons.Default.Screenshot,
                    onDismiss = {
                        showMediaProjectionDisclosure = false
                        finish()
                    },
                    onContinue = {
                        showMediaProjectionDisclosure = false
                        requestMediaProjection()
                    }
                )
            }
        }

        checkPermissionsAndStart()
    }

    // ── Permission chain ────────────────────────────────────

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = packageName + "/" +
            "com.autonion.automationcompanion.features.gesture_recording_playback.overlay.AutomationService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun checkPermissionsAndStart() {
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDisclosure = true
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            showOverlayDisclosure = true
            return
        }
        showMediaProjectionDisclosure = true
    }

    private fun requestMediaProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST)
    }

    @Deprecated("Deprecated in API but still needed for media projection result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            ACCESSIBILITY_PERMISSION_REQUEST -> {
                if (isAccessibilityServiceEnabled()) checkPermissionsAndStart()
                else {
                    Toast.makeText(this, "Accessibility service is required", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            OVERLAY_PERMISSION_REQUEST -> {
                if (Settings.canDrawOverlays(this)) checkPermissionsAndStart()
                else {
                    Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            MEDIA_PROJECTION_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    launchService(resultCode, data)
                } else {
                    Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun launchService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, SemanticAutomationService::class.java).apply {
            action = SemanticAutomationService.ACTION_START
            putExtra(SemanticAutomationService.EXTRA_RESULT_CODE, resultCode)
            putExtra(SemanticAutomationService.EXTRA_DATA, data)
            putExtra(SemanticAutomationService.EXTRA_COMMAND, pendingCommand)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Semantic agent started", Toast.LENGTH_SHORT).show()
        finish()
    }
}
