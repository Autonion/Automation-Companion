package com.autonion.automationcompanion.features.flow_automation.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionService
import com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract
import com.autonion.automationcompanion.features.visual_trigger.service.CaptureOverlayService

/**
 * A transparent helper activity designed to request MediaProjection permission
 * and then forward the permission token down to background services like 
 * CaptureOverlayService and FlowExecutionService.
 */
class FlowMediaProjectionActivity : ComponentActivity() {

    companion object {
        const val ACTION_START_VISUAL_OVERLAY = "ACTION_START_VISUAL_OVERLAY"
        const val ACTION_START_SCREEN_ML = "ACTION_START_SCREEN_ML"
        const val ACTION_RUN_FLOW = "ACTION_RUN_FLOW"
        
        const val EXTRA_FLOW_ID = "EXTRA_FLOW_ID"
        const val EXTRA_NODE_ID = "EXTRA_NODE_ID"
    }

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            when (intent.action) {
                ACTION_START_VISUAL_OVERLAY -> {
                    val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
                    val serviceIntent = Intent(this, CaptureOverlayService::class.java).apply {
                        action = "ACTION_START_OVERLAY" // Needs to match CaptureOverlayService onStartCommand
                        putExtra("EXTRA_RESULT_CODE", result.resultCode)
                        putExtra("EXTRA_RESULT_DATA", result.data)
                        putExtra(FlowOverlayContract.EXTRA_FLOW_MODE, true)
                        putExtra(FlowOverlayContract.EXTRA_FLOW_NODE_ID, nodeId)
                        intent.getStringExtra("EXTRA_FLOW_VISION_JSON")?.let { putExtra("EXTRA_FLOW_VISION_JSON", it) }
                        intent.getBooleanExtra("EXTRA_CLEAR_ON_START", false).let { if (it) putExtra("EXTRA_CLEAR_ON_START", true) }
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
                }
                ACTION_START_SCREEN_ML -> {
                    val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
                    val serviceIntent = Intent(this, com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService::class.java).apply {
                        action = "START_CAPTURE"
                        putExtra("resultCode", result.resultCode)
                        putExtra("data", result.data)
                        putExtra(FlowOverlayContract.EXTRA_FLOW_MODE, true)
                        putExtra(FlowOverlayContract.EXTRA_FLOW_NODE_ID, nodeId)
                        intent.getStringExtra("EXTRA_FLOW_ML_JSON")?.let { putExtra("EXTRA_FLOW_ML_JSON", it) }
                        intent.getBooleanExtra("EXTRA_CLEAR_ON_START", false).let { if (it) putExtra("EXTRA_CLEAR_ON_START", true) }
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
                }
                ACTION_RUN_FLOW -> {
                    val flowId = intent.getStringExtra(EXTRA_FLOW_ID) ?: ""
                    val serviceIntent = FlowExecutionService.createIntent(
                        context = this,
                        flowId = flowId,
                        resultCode = result.resultCode,
                        resultData = result.data
                    )
                    androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
                }
            }
        } else {
            Toast.makeText(this, "Screen record permission required for this action", Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }
}
