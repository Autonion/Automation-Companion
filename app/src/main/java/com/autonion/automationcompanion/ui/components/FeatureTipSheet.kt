@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.autonion.automationcompanion.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SheetBg = Color(0xFF13161E)
private val CardGlass = Color(0xFF1E2234)
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)

/**
 * A reusable first-visit tip bottom sheet shown when a user opens a
 * feature for the very first time.
 *
 * Design matches the app's dark glassmorphism aesthetic.
 *
 * @param title         Feature name displayed at the top
 * @param tips          List of tip strings. Text wrapped in ** ** is rendered bold + accented.
 * @param icon          Feature icon
 * @param iconColor     Icon container color
 * @param youtubeLink   Optional YouTube tutorial URL
 * @param onDismiss     Called when "Got it" is tapped — caller should mark tip as seen
 * @param onShowWalkthrough  Optional — if provided, shows a "Show me how" button
 */
@Composable
fun FeatureTipSheet(
    title: String,
    tips: List<String>,
    icon: ImageVector,
    iconColor: Color,
    youtubeLink: String? = null,
    onDismiss: () -> Unit,
    onShowWalkthrough: (() -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )
        },
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Icon ──
            val infiniteTransition = rememberInfiniteTransition(label = "tip_icon")
            val iconScale by infiniteTransition.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "icon_pulse"
            )

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .scale(iconScale)
                    .clip(RoundedCornerShape(18.dp))
                    .background(iconColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Title ──
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Quick tips to get started",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── Tips List ──
            Surface(
                color = CardGlass,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    tips.forEachIndexed { index, tip ->
                        TipRow(index = index + 1, text = tip)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Action Buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // "Got it" button — always shown
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.1f),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        "Got it",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }

                // "Show me how" button — only if walkthrough is available
                if (onShowWalkthrough != null) {
                    Button(
                        onClick = {
                            onDismiss()
                            onShowWalkthrough()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(AccentPurple, AccentBlue)
                                    ),
                                    RoundedCornerShape(14.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.School,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Show me how",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }
            }

            // ── YouTube Tutorial Link ──
            if (youtubeLink != null) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = { uriHandler.openUri(youtubeLink) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White.copy(alpha = 0.6f)
                    )
                ) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Watch Video Tutorial",
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * A single numbered tip row.
 * Text wrapped in ** ** is rendered bold in the accent color.
 */
@Composable
private fun TipRow(index: Int, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Number badge
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(AccentPurple.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$index",
                color = AccentPurple,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Tip text with bold parsing
        Text(
            text = parseBoldMarkdown(text),
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Parses text with **bold** markers and returns an AnnotatedString
 * where bold segments are rendered in white bold with accent tint.
 */
@Composable
private fun parseBoldMarkdown(text: String) = buildAnnotatedString {
    val regex = Regex("""\*\*(.+?)\*\*""")
    var lastIndex = 0

    regex.findAll(text).forEach { match ->
        // Append text before the match
        append(text.substring(lastIndex, match.range.first))
        // Append bold text
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
            append(match.groupValues[1])
        }
        lastIndex = match.range.last + 1
    }
    // Append remaining text
    if (lastIndex < text.length) {
        append(text.substring(lastIndex))
    }
}
