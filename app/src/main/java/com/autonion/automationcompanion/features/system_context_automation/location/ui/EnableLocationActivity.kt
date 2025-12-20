// file: EnableLocationActivity.kt
package com.autonion.automationcompanion.features.system_context_automation.location.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService
import java.util.concurrent.Executor
import androidx.compose.material3.MaterialTheme
import com.autonion.automationcompanion.features.system_context_automation.location.engine.accessibility.TileToggleFeature
import com.autonion.automationcompanion.features.system_context_automation.location.isAccessibilityEnabled

class EnableLocationActivity : AppCompatActivity() {

    private lateinit var executor: Executor

    // ActivityResult launcher for opening app settings (application details)
    private val openPanelLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // When user returns, try toggling via accessibility (same logic as before)
            tryAccessibilityToggleOrFinish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executor = ContextCompat.getMainExecutor(this)

        // Compose UI host (call your composable here)
        setContent {
            MaterialTheme {
                EnableLocationScreen(
                    openLocationPanel = { openLocationPanel() },
                    onFinishSuccess = {
                        setResult(RESULT_OK)
                        finish()
                    },
                    onFinishFailure = {
                        setResult(RESULT_CANCELED)
                        finish()
                    },
                    attemptBiometricAuth = { callback ->
                        // Provide a lambda the composable can call to request biometric auth.
                        // callback: (Boolean) -> Unit
                        attemptBiometric { ok -> callback(ok) }
                    }
                )
            }
        }
    }

    private fun attemptBiometric(onResult: (Boolean) -> Unit) {
        val bm = BiometricManager.from(this)
        val canAuth = bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onResult(false)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Authenticate to enable Location")
            .setSubtitle("Authorize this app to enable Location for scheduled automation")
            .setNegativeButtonText("Cancel")
            .build()

        // Because we extend AppCompatActivity (a FragmentActivity), this constructor is available
        val biometricPrompt = BiometricPrompt(
            this,                 // <-- this must be a FragmentActivity (AppCompatActivity is fine)
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onResult(false)
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onResult(true)
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    onResult(false)
                }
            }
        )

        // call authenticate on the instance
        biometricPrompt.authenticate(promptInfo)
    }

    private fun openLocationPanel() {
        val panel = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        openPanelLauncher.launch(panel)
    }

    fun requestAccessibilityPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }


    private fun tryAccessibilityToggleOrFinish() {

        if (!isAccessibilityEnabled(this)) {
            requestAccessibilityPermission(this)
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        TileToggleFeature.toggleLocation { success ->
            runOnUiThread {
                if (success) {
                    TrackingForegroundService.start(this)
                    setResult(RESULT_OK)
                } else {
                    setResult(RESULT_CANCELED)
                }
                finish()
            }
        }
    }

}
