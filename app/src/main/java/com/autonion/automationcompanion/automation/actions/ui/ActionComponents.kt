package com.autonion.automationcompanion.automation.actions.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autonion.automationcompanion.automation.actions.models.ConfiguredAction

/**
 * Redesigned action row with icon, title, subtitle, and a styled switch.
 * Renders as a mini glassmorphic card for visual appeal.
 */
@Composable
internal fun ActionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector = Icons.Rounded.ToggleOn,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark)
        MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
    else
        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardColor,
        tonalElevation = if (checked) 2.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored icon container
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = if (isDark) 0.18f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title + subtitle
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

/**
 * Section header for grouping actions into categories.
 */
@Composable
internal fun ActionSectionHeader(
    title: String,
    icon: ImageVector,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = iconTint
        )
    }
}

/**
 * Wrapper for action config sections with smooth expand/collapse animation.
 */
@Composable
internal fun AnimatedConfigSection(
    visible: Boolean,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        ) + fadeIn(animationSpec = tween(300, easing = FastOutSlowInEasing)),
        exit = shrinkVertically(
            animationSpec = tween(200, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(200))
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 6.dp, bottom = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .animateContentSize()
            ) {
                content()
            }
        }
    }
}

/**
 * Audio (Volume) action configuration UI.
 * Allows user to set ring and media volume levels.
 */
//@Composable
//internal fun AudioActionConfig(
//    action: ConfiguredAction.Audio,
//    onActionChanged: (ConfiguredAction.Audio) -> Unit
//) {
//    Column(Modifier.padding(start = 12.dp)) {
//        Text("Ring: ${action.ringVolume}")
//        Slider(
//            value = action.ringVolume.toFloat(),
//            onValueChange = { newValue ->
//                onActionChanged(action.copy(ringVolume = newValue.toInt()))
//            },
//            valueRange = 0f..7f,
//            modifier = Modifier.fillMaxWidth()
//        )
//
//        Text("Media: ${action.mediaVolume}")
//        Slider(
//            value = action.mediaVolume.toFloat(),
//            onValueChange = { newValue ->
//                onActionChanged(action.copy(mediaVolume = newValue.toInt()))
//            },
//            valueRange = 0f..15f,
//            modifier = Modifier.fillMaxWidth()
//        )
//    }
//}

/**
 * Brightness action configuration UI.
 */
@Composable
internal fun BrightnessActionConfig(
    action: ConfiguredAction.Brightness,
    onActionChanged: (ConfiguredAction.Brightness) -> Unit
) {
    Column {
        Text(
            "Brightness: ${action.level}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = action.level.toFloat(),
            onValueChange = { newValue ->
                onActionChanged(action.copy(level = newValue.toInt()))
            },
            valueRange = 10f..255f,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Do Not Disturb action configuration UI.
 * Currently no additional config needed beyond the toggle.
 */
@Composable
internal fun DndActionConfig() {
    // DND has no additional configuration options
    // The toggle itself is sufficient
}

/**
 * Send SMS action configuration UI.
 * Requires user to provide message and select at least one contact.
 */
@Composable
internal fun SmsActionConfig(
    action: ConfiguredAction.SendSms,
    onActionChanged: (ConfiguredAction.SendSms) -> Unit,
    onPickContactClicked: () -> Unit
) {
    Column {
        OutlinedTextField(
            value = action.message,
            onValueChange = { newMessage ->
                onActionChanged(action.copy(message = newMessage))
            },
            label = { Text("Message") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onPickContactClicked,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Rounded.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select contact")
        }

        if (action.contactsCsv.isBlank()) {
            Text(
                "⚠ Select at least one contact",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Text(
                action.contactsCsv,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ───────────── Display Actions ─────────────


/**
 * Auto-rotate action configuration UI.
 * Non-root: Uses Settings.System ACCELEROMETER_ROTATION
 */
@Composable
internal fun AutoRotateActionConfig(
    action: ConfiguredAction.AutoRotate,
    onActionChanged: (ConfiguredAction.AutoRotate) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (action.enabled) "Auto-rotate: ON" else "Auto-rotate: OFF",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(action.enabled, onCheckedChange = { newValue ->
            onActionChanged(action.copy(enabled = newValue))
        })
    }
}

/**
 * Screen timeout action configuration UI.
 * Non-root: Uses Settings.System SCREEN_OFF_TIMEOUT
 * Predefined options: 15s, 30s, 1m, 5m
 */
@Composable
internal fun ScreenTimeoutActionConfig(
    action: ConfiguredAction.ScreenTimeout,
    onActionChanged: (ConfiguredAction.ScreenTimeout) -> Unit
) {
    Column {
        Text("Screen Timeout", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(4.dp))
        val options = listOf(
            15_000 to "15 seconds",
            30_000 to "30 seconds",
            60_000 to "1 minute",
            300_000 to "5 minutes"
        )
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = action.durationMs == value,
                    onClick = { onActionChanged(action.copy(durationMs = value)) }
                )
                Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}


/**
 * Keep Screen Awake action configuration UI.
 * Non-root: Uses partial wake lock via foreground service.
 * Duration: acquired when trigger activates, released when deactivates.
 */
@Composable
internal fun KeepScreenAwakeActionConfig(
    action: ConfiguredAction.KeepScreenAwake,
    onActionChanged: (ConfiguredAction.KeepScreenAwake) -> Unit
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (action.enabled) "Keep Screen Awake: ON" else "Keep Screen Awake: OFF",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Switch(action.enabled, onCheckedChange = { newValue ->
                onActionChanged(action.copy(enabled = newValue))
            })
        }
        Text(
            "Active while this automation is triggered",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
