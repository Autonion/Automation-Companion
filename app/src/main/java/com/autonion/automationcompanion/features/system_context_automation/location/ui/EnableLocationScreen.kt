package com.autonion.automationcompanion.features.system_context_automation.location.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.system_context_automation.location.engine.accessibility.TileToggleFeature
import com.autonion.automationcompanion.features.system_context_automation.location.engine.location_receiver.TrackingForegroundService
import com.autonion.automationcompanion.features.system_context_automation.location.isAccessibilityEnabled
import com.autonion.automationcompanion.ui.components.AuroraBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun EnableLocationScreen(
    openLocationPanel: () -> Unit,
    attemptBiometricAuth: (( (Boolean) -> Unit ) -> Unit)? = null,
    onFinishSuccess: () -> Unit,
    onFinishFailure: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    var loading by remember { mutableStateOf(false) }
    var showAccessibilityDialog by remember { mutableStateOf(false) }

    // Logic remains same
    LaunchedEffect(Unit) {
        if (attemptBiometricAuth != null) {
            attemptBiometricAuth { success ->
                if (success) {
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
                    openLocationPanel()
                }
            }
        } else {
            openLocationPanel()
        }
    }

    // Pulsing icon animation
    val infiniteTransition = rememberInfiniteTransition(label = "locationPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Content entrance animation
    val contentAlpha = remember { Animatable(0f) }
    val contentSlide = remember { Animatable(30f) }
    LaunchedEffect(Unit) {
        delay(200)
        launch {
            contentAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
        launch {
            contentSlide.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
        }
    }

    AuroraBackground {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(40.dp)
                    .graphicsLayer {
                        alpha = contentAlpha.value
                        translationY = contentSlide.value
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Pulsing location icon with gradient background circle
                Box(contentAlignment = Alignment.Center) {
                    // Outer glow ring
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha * 0.3f)
                            )
                    )
                    // Inner icon container
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = if (isDark) 0.2f else 0.12f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Enable Location",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Automation requires location services to be active for geofence triggers to work.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = 24.sp
                )

                Spacer(modifier = Modifier.height(36.dp))

                if (loading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                } else {
                    Button(
                        onClick = openLocationPanel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Open Settings",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // Accessibility dialog
            if (showAccessibilityDialog) {
                AlertDialog(
                    onDismissRequest = {},
                    title = {
                        Text(
                            "Enable Accessibility",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Text(
                            "Automation requires Accessibility to toggle Location automatically. Please enable it in Accessibility settings."
                        )
                    },
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
                    shape = RoundedCornerShape(20.dp)
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
        onStop()
        if (success) onSuccess() else onFallback()
    }
}

@Preview
@Composable
fun EnableLocationPreview() {
    EnableLocationScreen(
        openLocationPanel = {},
        attemptBiometricAuth = {},
        onFinishSuccess = {},
        onFinishFailure = {}
    )
}