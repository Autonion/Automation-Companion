package com.autonion.automationcompanion.features.visual_trigger.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.autonion.automationcompanion.features.visual_trigger.service.CaptureOverlayService
import com.autonion.automationcompanion.ui.theme.AppTheme

class VisionEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imagePath = intent.getStringExtra("IMAGE_PATH")
        val presetId = intent.getStringExtra("PRESET_ID")
        val presetName = intent.getStringExtra("EXTRA_PRESET_NAME") ?: "New Automation"

        if (imagePath == null && presetId == null) {
            Toast.makeText(this, "No image or preset to edit", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            AppTheme {
                VisionEditorScreen(
                    imagePath = imagePath ?: "",
                    presetId = presetId,
                    presetName = presetName,
                    onSaved = {
                        // Re-show the capture overlay so user can capture more or cancel
                        showOverlayAgain()
                        finish()
                    },
                    onCancel = {
                        showOverlayAgain()
                        finish()
                    },
                    onRecapture = {
                        // Just close this activity — overlay will re-show and user can capture again
                        showOverlayAgain()
                        finish()
                    }
                )
            }
        }
    }

    private fun showOverlayAgain() {
        try {
            val intent = Intent(this, CaptureOverlayService::class.java).apply {
                action = "ACTION_SHOW_OVERLAY"
            }
            startService(intent)
        } catch (_: Exception) {
            // Service may not be running
        }
    }
}
