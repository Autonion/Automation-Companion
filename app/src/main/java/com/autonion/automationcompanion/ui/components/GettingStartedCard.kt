@file:OptIn(ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences

private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)
private val AccentGreen = Color(0xFF00E676)
private val CardBg = Color(0xFF1A1D2E)

/**
 * A dismissible "Getting Started" progress checklist card for the home screen.
 *
 * Shows setup milestones with a progress bar and interactive rows.
 * Auto-hides when all steps are complete or when dismissed by the user.
 *
 * @param isAccessibilityEnabled  Whether Accessibility Service is on
 * @param isAIConnected           Whether any LLM connection is active
 * @param hasCreatedAutomation    Whether the user has created at least one automation
 * @param onConnectAI             Called when "Connect AI" row is tapped
 * @param onCreateAutomation      Called when "Create automation" row is tapped
 * @param onDismiss               Called when the user taps the dismiss button
 */
@Composable
fun GettingStartedCard(
    isAIConnected: Boolean,
    hasCreatedAutomation: Boolean,
    onConnectAI: () -> Unit,
    onCreateAutomation: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) CardBg else MaterialTheme.colorScheme.surface
    val textColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    // Calculate completion
    val steps = listOf(
        ChecklistStep("Install app", Icons.Default.Download, true),
        ChecklistStep("Connect AI", Icons.Default.SmartToy, isAIConnected),
        ChecklistStep("Create your first automation", Icons.Default.AutoFixHigh, hasCreatedAutomation)
    )
    val completedCount = steps.count { it.isComplete }
    val totalCount = steps.size

    // Don't show if all complete
    if (completedCount == totalCount) return

    val progress by animateFloatAsState(
        targetValue = completedCount.toFloat() / totalCount.toFloat(),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "progress"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        color = cardBg,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.horizontalGradient(
                listOf(
                    AccentPurple.copy(alpha = if (isDark) 0.3f else 0.4f),
                    AccentBlue.copy(alpha = if (isDark) 0.3f else 0.4f)
                )
            )
        ),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.RocketLaunch,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Getting Started",
                    color = textColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = textColor.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Progress bar
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = AccentPurple,
                    trackColor = if (isDark) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "$completedCount/$totalCount",
                    color = secondaryTextColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))

            // Checklist items
            steps.forEachIndexed { index, step ->
                ChecklistRow(
                    step = step,
                    isDark = isDark,
                    baseTextColor = textColor,
                    onClick = when {
                        step.isComplete -> null
                        step.label == "Connect AI" -> onConnectAI
                        step.label == "Create your first automation" -> onCreateAutomation
                        else -> null
                    }
                )
                if (index < steps.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private data class ChecklistStep(
    val label: String,
    val icon: ImageVector,
    val isComplete: Boolean
)

@Composable
private fun ChecklistRow(
    step: ChecklistStep,
    isDark: Boolean,
    baseTextColor: Color,
    onClick: (() -> Unit)?
) {
    val textColor by animateColorAsState(
        targetValue = if (step.isComplete) baseTextColor.copy(alpha = if (isDark) 0.4f else 0.45f) else baseTextColor.copy(alpha = if (isDark) 0.85f else 0.9f),
        animationSpec = tween(300),
        label = "textColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClick)
                    .background(if (isDark) Color.White.copy(alpha = 0.03f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                else Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        if (step.isComplete) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(AccentGreen.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = AccentGreen,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(1.5.dp, if (isDark) Color.White.copy(alpha = 0.2f) else baseTextColor.copy(alpha = 0.25f), CircleShape)
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            step.label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (step.isComplete) FontWeight.Normal else FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )

        if (!step.isComplete && onClick != null) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (isDark) Color.White.copy(alpha = 0.3f) else baseTextColor.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
