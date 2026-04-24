package com.autonion.automationcompanion.features.screen_understanding_ml.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Screenshot
import com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionDisclosureDialog
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.features.screen_understanding_ml.core.ScreenUnderstandingService
import com.autonion.automationcompanion.ui.theme.AppTheme

class SetupFlowActivity : ComponentActivity() {

    private val MEDIA_PROJECTION_REQUEST_CODE = 100
    private val OVERLAY_PERMISSION_REQUEST_CODE = 101
    private val ACCESSIBILITY_PERMISSION_REQUEST_CODE = 102

    private var showAccessibilityDisclosure by mutableStateOf(false)
    private var showOverlayDisclosure by mutableStateOf(false)
    private var showMediaProjectionDisclosure by mutableStateOf(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                SetupFlowScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SetupFlowScreen() {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Starting Automation") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(alpha),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Requesting permissions…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Allow accessibility, overlay, and screen capture when prompted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }
        }

        LaunchedEffect(Unit) {
            checkPermissionsAndStart()
        }

        PermissionDisclosureDialog(
            showDialog = showAccessibilityDisclosure,
            title = "Accessibility Service Required",
            description = "Autonion needs Accessibility Service to automate screen interactions and taps for Screen Context AI. Please enable it in the next screen.",
            icon = Icons.Default.Accessibility,
            onDismiss = {
                showAccessibilityDisclosure = false
                finish()
            },
            onContinue = {
                showAccessibilityDisclosure = false
                val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivityForResult(intent, ACCESSIBILITY_PERMISSION_REQUEST_CODE)
            }
        )

        PermissionDisclosureDialog(
            showDialog = showOverlayDisclosure,
            title = "Display Over Other Apps Required",
            description = "Autonion needs to display over other apps to capture the screen and show the Screen Context AI UI elements.",
            icon = Icons.Default.Layers,
            onDismiss = {
                showOverlayDisclosure = false
                finish()
            },
            onContinue = {
                showOverlayDisclosure = false
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            }
        )

        PermissionDisclosureDialog(
            showDialog = showMediaProjectionDisclosure,
            title = "Screen Capture Required",
            description = "Autonion needs to capture your screen to analyze UI elements for Screen Context AI. The screen content is processed locally on your device and is not stored or shared.",
            icon = Icons.Default.Screenshot,
            onDismiss = {
                showMediaProjectionDisclosure = false
                finish()
            },
            onContinue = {
                showMediaProjectionDisclosure = false
                startMediaProjection()
            }
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = packageName + "/" +
            "com.autonion.automationcompanion.features.gesture_recording_playback.overlay.AutomationService"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun checkPermissionsAndStart() {
        // Step 1: Accessibility Service
        if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDisclosure = true
            return
        }
        // Step 2: Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            showOverlayDisclosure = true
            return
        }
        // Step 3: Media Projection
        showMediaProjectionDisclosure = true
    }

    private fun startMediaProjection() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), MEDIA_PROJECTION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == ACCESSIBILITY_PERMISSION_REQUEST_CODE) {
            if (isAccessibilityServiceEnabled()) {
                // Continue the permission chain
                checkPermissionsAndStart()
            } else {
                Toast.makeText(this, "Accessibility service is required for automation", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Settings.canDrawOverlays(this)) {
                checkPermissionsAndStart() // Continue chain
            } else {
                Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else if (requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                // Start Service and pass intent data
                val presetName = intent.getStringExtra("presetName") ?: ""
                android.util.Log.d("SetupFlow", "Passed presetName: '$presetName'")
                val playPresetId = intent.getStringExtra("ACTION_REQUEST_PERMISSION_PLAY_PRESET")
                
                val serviceIntent = Intent(this, ScreenUnderstandingService::class.java).apply {
                    putExtra("resultCode", resultCode)
                    putExtra("data", data)
                    putExtra("presetName", presetName)
                    if (playPresetId != null) {
                        putExtra("playPresetId", playPresetId)
                    }
                    // Forward debug mode flag
                    if (intent.getBooleanExtra("debugMode", false)) {
                        putExtra("debugMode", true)
                    }
                    // Forward model file selection
                    intent.getStringExtra("modelFile")?.let {
                        putExtra("modelFile", it)
                    }
                    action = "START_CAPTURE"
                }
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                finish()
            } else {
                Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
                finish() // Ensure we finish even on denial
            }
        }
    }
}
