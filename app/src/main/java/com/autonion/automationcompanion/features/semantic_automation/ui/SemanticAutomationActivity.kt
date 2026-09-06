package com.autonion.automationcompanion.features.semantic_automation.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Layers
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
 *   Accessibility → Overlay
 * then starts [SemanticAutomationService] and finishes itself.
 *
 * MediaProjection is NOT required — the engine uses the Accessibility tree
 * for UI understanding, which doesn't need screen capture.
 */
class SemanticAutomationActivity : ComponentActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 201
        private const val ACCESSIBILITY_PERMISSION_REQUEST = 202
    }

    private var pendingCommand: String = ""

    private var showAccessibilityDisclosure by mutableStateOf(false)
    private var showOverlayDisclosure by mutableStateOf(false)

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
        // All permissions granted — launch the service directly
        launchService()
    }

    @Deprecated("Deprecated in API but still needed for permission result callbacks")
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
        }
    }

    private fun launchService() {
        val serviceIntent = Intent(this, SemanticAutomationService::class.java).apply {
            action = SemanticAutomationService.ACTION_START
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
