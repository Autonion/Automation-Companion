package com.autonion.automationcompanion.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AccentOrange = Color(0xFFFF9800)
private val AccentAmber = Color(0xFFFFCA28)

/**
 * Shared overlay shown when an AI-powered screen lacks a required connection.
 *
 * Use with a blurred background (Modifier.blur(12.dp)) behind the content area,
 * and place this overlay on top within a Box.
 *
 * @param title Main title (default "Connection Required")
 * @param message Description of what's needed
 * @param steps Numbered instruction steps
 * @param icon Override icon (default CloudOff)
 * @param optionalChip Optional amber info line (e.g. "Lemur extension not connected")
 */
@Composable
fun ConnectionRequiredOverlay(
    title: String = "Connection Required",
    message: String,
    steps: List<String> = emptyList(),
    icon: ImageVector = Icons.Default.CloudOff,
    optionalChip: String? = null
) {
    // Subtle pulse on the icon
    val infiniteTransition = rememberInfiniteTransition(label = "overlay_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "overlay_icon_pulse"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xCC1A1A1A))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = AccentOrange,
            modifier = Modifier
                .size(48.dp)
                .alpha(pulseAlpha)
        )

        Text(
            title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        Text(
            message,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        if (steps.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                steps.forEachIndexed { index, step ->
                    Text(
                        "${index + 1}. $step",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Optional amber info chip (non-blocking, informational)
        if (optionalChip != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AccentAmber.copy(alpha = 0.15f))
                    .border(1.dp, AccentAmber.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = AccentAmber,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    optionalChip,
                    color = AccentAmber,
                    fontSize = 11.sp
                )
            }
        }
    }
}
