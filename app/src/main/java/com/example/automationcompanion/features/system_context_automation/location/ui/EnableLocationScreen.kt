package com.example.automationcompanion.features.system_context_automation.location.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.example.automationcompanion.features.system_context_automation.location.engine.accessibility.TileToggleFeature
import com.example.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService
import com.example.automationcompanion.features.system_context_automation.location.isAccessibilityEnabled

@Composable
fun EnableLocationScreen(
    openLocationPanel: () -> Unit,
    attemptBiometricAuth: (( (Boolean) -> Unit ) -> Unit)? = null,
    onFinishSuccess: () -> Unit,
    onFinishFailure: () -> Unit
) {
    val context = LocalContext.current

    var loading by remember { mutableStateOf(false) }
    var showAccessibilityDialog by remember { mutableStateOf(false) }

    // We do not launch biometric here automatically — the Activity can call attemptBiometricAuth when needed.
    // But for parity with old flow we can try to run it on start if provided:
    LaunchedEffect(Unit) {
        // If activity provided biometric trigger, request it.
        if (attemptBiometricAuth != null) {
            attemptBiometricAuth { success ->
                if (success) {
                    // attempt toggle via accessibility
                    tryAccessibilityToggle(
                        context = context,
                        onStart = { loading = true },
                        onStop = { loading = false },
                        onSuccess = {
                            TrackingForegroundService.start(context)
                            onFinishSuccess()
                        },
                        onFallback = { showAccessibilityDialog = true; onFinishFailure() }
                    )
                } else {
                    // open app details so user can enable location
                    openLocationPanel()
                }
            }
        } else {
            // if no biometric available, open panel
            openLocationPanel()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            if (showAccessibilityDialog) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Enable Accessibility") },
                    text = { Text("Automation requires Accessibility to toggle Location automatically. Please enable it in Accessibility settings.") },
                    confirmButton = {
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            onFinishFailure()
                        }) {
                            Text("Open Settings")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { onFinishFailure() }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}

private fun tryAccessibilityToggle(
    context: android.content.Context,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSuccess: () -> Unit,
    onFallback: () -> Unit
) {
    if (!isAccessibilityEnabled(context)) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        onFallback()
        return
    }

    onStart()

    TileToggleFeature.toggleLocation { success ->
        // already on main thread
        onStop()
        if (success) onSuccess() else onFallback()
    }
}



@Preview
@Composable
fun EnableLocationPreview() {
    EnableLocationScreen(openLocationPanel = {}, attemptBiometricAuth = {}, onFinishSuccess = {}, onFinishFailure = {})
}