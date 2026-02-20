package com.autonion.automationcompanion.features.visual_trigger.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract
import com.autonion.automationcompanion.features.visual_trigger.service.CaptureOverlayService
import com.autonion.automationcompanion.ui.theme.AppTheme

class VisionEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imagePath = intent.getStringExtra("IMAGE_PATH")
        val presetId = intent.getStringExtra("PRESET_ID")
        val presetName = intent.getStringExtra("EXTRA_PRESET_NAME") ?: "New Automation"
        
        val isFlowMode = intent.getBooleanExtra(FlowOverlayContract.EXTRA_FLOW_MODE, false)
        val flowNodeId = intent.getStringExtra(FlowOverlayContract.EXTRA_FLOW_NODE_ID)

        val flowVisionJson = intent.getStringExtra("EXTRA_FLOW_VISION_JSON")
        val clearOnStart = intent.getBooleanExtra("EXTRA_CLEAR_ON_START", false)

        if (imagePath == null && presetId == null && flowVisionJson == null) {
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
                    isFlowMode = isFlowMode,
                    flowNodeId = flowNodeId,
                    onSaved = { tempFilePath ->
                        if (isFlowMode && flowNodeId != null && tempFilePath != null) {
                            val resultIntent = Intent(FlowOverlayContract.ACTION_FLOW_VISION_DONE).apply {
                                putExtra(FlowOverlayContract.EXTRA_RESULT_NODE_ID, flowNodeId)
                                putExtra(FlowOverlayContract.EXTRA_RESULT_FILE_PATH, tempFilePath)
                                putExtra(FlowOverlayContract.EXTRA_RESULT_IMAGE_PATH, imagePath)
                            }
                            LocalBroadcastManager.getInstance(this@VisionEditorActivity).sendBroadcast(resultIntent)
                        } 
                        // Always re-show the overlay so user can capture more or close manually
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
                    },
                    flowVisionJson = flowVisionJson
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
