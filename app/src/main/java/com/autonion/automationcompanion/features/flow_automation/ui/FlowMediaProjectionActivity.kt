package com.autonion.automationcompanion.features.flow_automation.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.autonion.automationcompanion.features.flow_automation.data.FlowRepository
import com.autonion.automationcompanion.features.flow_automation.engine.FlowExecutionService
import com.autonion.automationcompanion.features.flow_automation.engine.FlowOverlayContract
import com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionDisclosureDialog
import com.autonion.automationcompanion.features.visual_trigger.service.CaptureOverlayService
import com.autonion.automationcompanion.features.visual_trigger.service.VisionExecutionService
import com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService
import com.autonion.automationcompanion.core.vision.createAutomationCaptureIntent
import com.autonion.automationcompanion.ui.theme.AppTheme

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
        const val ACTION_RECOVER_VISUAL_TRIGGER = "ACTION_RECOVER_VISUAL_TRIGGER"
        const val ACTION_RECOVER_SCREEN_ML = "ACTION_RECOVER_SCREEN_ML"
        
        const val EXTRA_FLOW_ID = "EXTRA_FLOW_ID"
        const val EXTRA_NODE_ID = "EXTRA_NODE_ID"
        const val EXTRA_PRESET_ID = "EXTRA_PRESET_ID"
        const val EXTRA_RECOVERY_REQUEST = "EXTRA_RECOVERY_REQUEST"
        const val EXTRA_RESUME_AFTER_RECOVERY = "EXTRA_RESUME_AFTER_RECOVERY"
        const val EXTRA_CAPTURE_RECOVERY_ID = "EXTRA_CAPTURE_RECOVERY_ID"

        fun screenMlRecoveryIntent(context: Context, recoveryId: String): Intent =
            Intent(context, FlowMediaProjectionActivity::class.java).apply {
                action = ACTION_RECOVER_SCREEN_ML
                putExtra(EXTRA_RECOVERY_REQUEST, true)
                putExtra(EXTRA_CAPTURE_RECOVERY_ID, recoveryId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        fun flowRecoveryIntent(context: Context, flowId: String): Intent =
            Intent(context, FlowMediaProjectionActivity::class.java).apply {
                action = ACTION_RUN_FLOW
                putExtra(EXTRA_FLOW_ID, flowId)
                putExtra(EXTRA_RECOVERY_REQUEST, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        fun visualTriggerRecoveryIntent(
            context: Context,
            presetId: String,
            resumeAfterRecovery: Boolean
        ): Intent = Intent(context, FlowMediaProjectionActivity::class.java).apply {
            action = ACTION_RECOVER_VISUAL_TRIGGER
            putExtra(EXTRA_PRESET_ID, presetId)
            putExtra(EXTRA_RECOVERY_REQUEST, true)
            putExtra(EXTRA_RESUME_AFTER_RECOVERY, resumeAfterRecovery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    private var showMediaProjectionDisclosure by mutableStateOf(true)
    private var requiresFullScreenCapture by mutableStateOf(false)
    private var projectionRequestStarted = false
    private val isRecoveryRequest: Boolean
        get() = intent.getBooleanExtra(EXTRA_RECOVERY_REQUEST, false)

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) requestProjectionIfAvailable()
        }
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
                        intent.getStringExtra("EXTRA_FLOW_NODE_MODE")?.let { putExtra("EXTRA_FLOW_NODE_MODE", it) }
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
                ACTION_RECOVER_VISUAL_TRIGGER -> {
                    val presetId = intent.getStringExtra(EXTRA_PRESET_ID) ?: return@registerForActivityResult
                    val serviceIntent = Intent(this, VisionExecutionService::class.java).apply {
                        action = VisionExecutionService.ACTION_START_EXECUTION
                        putExtra(VisionExecutionService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(VisionExecutionService.EXTRA_RESULT_DATA, result.data)
                        putExtra(VisionExecutionService.EXTRA_PRESET_ID, presetId)
                        putExtra(
                            VisionExecutionService.EXTRA_START_RUNNING,
                            intent.getBooleanExtra(EXTRA_RESUME_AFTER_RECOVERY, false)
                        )
                    }
                    ContextCompat.startForegroundService(this, serviceIntent)
                }
                ACTION_RECOVER_SCREEN_ML -> {
                    val recoveryId = intent.getStringExtra(EXTRA_CAPTURE_RECOVERY_ID)
                    if (ScreenUnderstandingService.instance?.isAwaitingProjection(recoveryId) == true) {
                        val serviceIntent = Intent(this, ScreenUnderstandingService::class.java).apply {
                            action = ScreenUnderstandingService.ACTION_RESTORE_PROJECTION
                            putExtra(EXTRA_CAPTURE_RECOVERY_ID, recoveryId)
                            putExtra("resultCode", result.resultCode)
                            putExtra("data", result.data)
                        }
                        ContextCompat.startForegroundService(this, serviceIntent)
                    }
                }
            }
        } else {
            Toast.makeText(
                this,
                if (isRecoveryRequest) "Screen capture remains stopped" else "Screen capture permission is required",
                Toast.LENGTH_SHORT
            ).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionRequestStarted = savedInstanceState?.getBoolean("projectionRequestStarted") ?: false
        requiresFullScreenCapture = shouldRequireFullScreenCapture()
        showMediaProjectionDisclosure = !isRecoveryRequest

        if (isRecoveryRequest) {
            registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        }

        setContent {
            AppTheme {
                PermissionDisclosureDialog(
                    showDialog = showMediaProjectionDisclosure,
                    title = "Screen Capture Required",
                    description = if (requiresFullScreenCapture) {
                        "Autonion needs to capture your screen to detect visual elements and execute automation flows. Because this flow switches apps, Android will request the entire display. Screen content is processed locally and is not stored or shared."
                    } else {
                        "Autonion needs to capture your screen to detect visual elements and execute automation flows. The screen content is processed locally on your device and is not stored or shared."
                    },
                    icon = Icons.Default.Screenshot,
                    onDismiss = {
                        showMediaProjectionDisclosure = false
                        finish()
                        overridePendingTransition(0, 0)
                    },
                    onContinue = {
                        showMediaProjectionDisclosure = false
                        requestProjectionIfAvailable()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isRecoveryRequest) requestProjectionIfAvailable()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("projectionRequestStarted", projectionRequestStarted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isRecoveryRequest) runCatching { unregisterReceiver(userPresentReceiver) }
        super.onDestroy()
    }

    private fun requestProjectionIfAvailable() {
        if (projectionRequestStarted || isFinishing) return
        if (intent.action == ACTION_RECOVER_SCREEN_ML &&
            ScreenUnderstandingService.instance?.isAwaitingProjection(
                intent.getStringExtra(EXTRA_CAPTURE_RECOVERY_ID)
            ) != true
        ) {
            finish()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) return
        projectionRequestStarted = true
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createAutomationCaptureIntent(requiresFullScreenCapture))
    }

    private fun shouldRequireFullScreenCapture(): Boolean {
        if (intent.action != ACTION_RUN_FLOW) return false
        val flowId = intent.getStringExtra(EXTRA_FLOW_ID) ?: return false
        val graph = FlowRepository(this).load(flowId) ?: return false
        return graph.requiresFullDisplayCapture()
    }
}
