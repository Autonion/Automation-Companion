package com.autonion.automationcompanion.features.omni_chatbot.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.omni_chatbot.companion.StepType
import com.autonion.automationcompanion.features.omni_chatbot.companion.WalkthroughScript
import kotlin.math.roundToInt

// ─── Colors ──────────────────────────────────────────────
private val CompanionPurple = Color(0xFF7C4DFF)
private val CompanionBlue = Color(0xFF448AFF)
private val CompanionTeal = Color(0xFF00BFA5)
private val BubbleBg = Color(0xFF1A1D2E)
private val BubbleBorder = Color.White.copy(alpha = 0.1f)

/**
 * A compact, draggable "Shimeji-style" companion widget.
 *
 * Consists of a small mascot avatar bubble that can be dragged anywhere on screen.
 * Tapping it toggles a compact speech bubble with the current walkthrough instruction.
 * Designed to be non-intrusive and not block screen content.
 *
 * @param walkthrough   The active walkthrough script.
 * @param stepIndex     Current step index (0-based).
 * @param onPrevious    Called when the user taps "Previous".
 * @param onNext        Called when the user taps "Next".
 * @param onDismiss     Called when the user taps close/stop.
 */
@Composable
fun CompanionFloatingBar(
    walkthrough: WalkthroughScript,
    stepIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val step = walkthrough.steps.getOrNull(stepIndex) ?: return
    val totalSteps = walkthrough.steps.size
    val isFirstStep = stepIndex == 0
    val isLastStep = stepIndex == totalSteps - 1

    // Whether the speech bubble is expanded
    var isBubbleExpanded by remember { mutableStateOf(true) }

    // Draggable offset
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    var offsetX by remember { mutableFloatStateOf(screenWidthPx - with(density) { 64.dp.toPx() }) }
    var offsetY by remember { mutableFloatStateOf(screenHeightPx - with(density) { 180.dp.toPx() }) }

    // Subtle breathing animation for the mascot
    val infiniteTransition = rememberInfiniteTransition(label = "companionBreath")
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    // Step change: auto-expand bubble and animate text
    LaunchedEffect(stepIndex) {
        isBubbleExpanded = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        // ── Speech Bubble (positioned relative to mascot) ──
        AnimatedVisibility(
            visible = isBubbleExpanded,
            enter = fadeIn(tween(200)) + scaleIn(
                tween(250),
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
            ),
            exit = fadeOut(tween(150)) + scaleOut(
                tween(200),
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
            ),
            modifier = Modifier.offset {
                IntOffset(
                    // Bubble positioned to the left of the mascot
                    (offsetX - with(density) { 232.dp.toPx() }).roundToInt()
                        .coerceAtLeast(with(density) { 8.dp.toPx().roundToInt() }),
                    // Bubble positioned above the mascot
                    (offsetY - with(density) { 52.dp.toPx() }).roundToInt()
                        .coerceAtLeast(with(density) { 8.dp.toPx().roundToInt() })
                )
            }
        ) {
            SpeechBubble(
                featureName = walkthrough.featureName,
                instruction = step.instruction,
                highlightHint = step.highlightHint,
                stepType = step.stepType,
                stepIndex = stepIndex,
                totalSteps = totalSteps,
                isFirstStep = isFirstStep,
                isLastStep = isLastStep,
                onPrevious = onPrevious,
                onNext = onNext,
                onDismiss = onDismiss,
                onCollapse = { isBubbleExpanded = false }
            )
        }

        // ── Mascot Avatar (draggable) ──
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(48.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(CompanionPurple, CompanionBlue)
                    )
                )
                .clickable { isBubbleExpanded = !isBubbleExpanded }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x)
                            .coerceIn(0f, screenWidthPx - 48.dp.toPx())
                        offsetY = (offsetY + dragAmount.y)
                            .coerceIn(0f, screenHeightPx - 48.dp.toPx())
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                when (step.stepType) {
                    StepType.NAVIGATE -> Icons.Default.Explore
                    StepType.OBSERVE -> Icons.Default.Visibility
                    StepType.ACTION -> Icons.Default.TouchApp
                },
                contentDescription = "Companion",
                modifier = Modifier
                    .size(24.dp)
                    .scale(breathScale),
                tint = Color.White
            )
        }

        // ── Step counter badge on mascot ──
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (offsetX + with(density) { 28.dp.toPx() }).roundToInt(),
                        (offsetY - with(density) { 4.dp.toPx() }).roundToInt()
                    )
                }
                .clip(RoundedCornerShape(8.dp))
                .background(CompanionTeal)
                .padding(horizontal = 5.dp, vertical = 1.dp)
                .clickable { isBubbleExpanded = !isBubbleExpanded },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${stepIndex + 1}/$totalSteps",
                color = Color.White,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Compact speech bubble with walkthrough instruction and mini controls.
 */
@Composable
private fun SpeechBubble(
    featureName: String,
    instruction: String,
    highlightHint: String?,
    stepType: StepType,
    stepIndex: Int,
    totalSteps: Int,
    isFirstStep: Boolean,
    isLastStep: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    onCollapse: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = BubbleBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        modifier = Modifier.widthIn(max = 230.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            // ── Header: Feature name + collapse/dismiss ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Compact step-type dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when (stepType) {
                                StepType.NAVIGATE -> CompanionBlue
                                StepType.OBSERVE -> CompanionTeal
                                StepType.ACTION -> CompanionPurple
                            }
                        )
                )
                Spacer(Modifier.width(6.dp))

                Text(
                    text = featureName,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Collapse button
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Collapse",
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onCollapse() }
                )
                Spacer(Modifier.width(4.dp))

                // Dismiss
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Stop walkthrough",
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onDismiss() }
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── Instruction text ──
            AnimatedContent(
                targetState = stepIndex,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 3 })
                        .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 3 })
                },
                label = "instructionAnim"
            ) { animatedStepIndex ->
                // AnimatedContent needs us to use the target state for correct transitions
                key(animatedStepIndex) {
                    Text(
                        text = instruction,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Highlight hint ──
            highlightHint?.let { hint ->
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AdsClick,
                        contentDescription = null,
                        tint = CompanionTeal.copy(alpha = 0.6f),
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = hint,
                        color = CompanionTeal.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Mini controls ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous
                if (!isFirstStep) {
                    Icon(
                        Icons.AutoMirrored.Filled.NavigateBefore,
                        contentDescription = "Previous",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { onPrevious() }
                            .padding(3.dp)
                    )
                } else {
                    Spacer(Modifier.width(22.dp))
                }

                // Progress dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (i in 0 until totalSteps) {
                        Box(
                            modifier = Modifier
                                .size(if (i == stepIndex) 6.dp else 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i == stepIndex) CompanionTeal
                                    else if (i < stepIndex) CompanionPurple.copy(alpha = 0.5f)
                                    else Color.White.copy(alpha = 0.15f)
                                )
                        )
                    }
                }

                // Next / Finish
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isLastStep) CompanionTeal.copy(alpha = 0.9f)
                            else CompanionPurple.copy(alpha = 0.8f)
                        )
                        .clickable { onNext() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isLastStep) "Done ✓" else "Next →",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
