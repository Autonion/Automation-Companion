package com.autonion.automationcompanion.features.gesture_recording_playback.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private const val DIALOG_ANIM_DURATION = 220

@Composable
fun NewPresetDialog(onCreate: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val scale = remember { Animatable(0.9f) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(DIALOG_ANIM_DURATION, easing = FastOutSlowInEasing))
        alpha.animateTo(1f, tween(DIALOG_ANIM_DURATION))
    }

    fun dismissThen(callback: () -> Unit) {
        scope.launch {
            scale.animateTo(0.95f, tween(120))
            alpha.animateTo(0f, tween(120))
            callback()
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "New Automation",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Preset name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    singleLine = true
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { dismissThen(onCancel) }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            dismissThen {
                                if (text.trim().isNotEmpty()) onCreate(text.trim())
                            }
                        }
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(presetName: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val scale = remember { Animatable(0.9f) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scale.animateTo(1f, tween(DIALOG_ANIM_DURATION, easing = FastOutSlowInEasing))
        alpha.animateTo(1f, tween(DIALOG_ANIM_DURATION))
    }

    fun dismissThen(callback: () -> Unit) {
        scope.launch {
            scale.animateTo(0.95f, tween(120))
            alpha.animateTo(0f, tween(120))
            callback()
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Delete Preset",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Are you sure you want to delete '$presetName'?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { dismissThen(onCancel) }) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { dismissThen(onConfirm) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}
