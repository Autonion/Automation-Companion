package com.autonion.automationcompanion.features.omni_chatbot.ui

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.omni_chatbot.OmniChatbotViewModel
import com.autonion.automationcompanion.features.omni_chatbot.model.*
import java.util.Date

// ─── Colors ──────────────────────────────────────────────
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)
private val AccentGreen = Color(0xFF48C9B0)
private val AccentRed = Color(0xFFFF6B6B)
private val AccentOrange = Color(0xFFFF9800)
private val SheetBg = Color(0xFF0D1117)
private val CardGlass = Color(0xFF1A1D2E)
private val InputBarBg = Color(0xFF1A1D2E).copy(alpha = 0.85f)
private val UserBubbleGrad = listOf(AccentPurple, AccentBlue)
private val SystemBubbleBg = Color(0xFF1E2030)

// ═══════════════════════════════════════════════════════════
//  MAIN: FAB + BOTTOM SHEET
// ═══════════════════════════════════════════════════════════

/**
 * Global Omni-Chatbot overlay.
 * Place this in the app root so the FAB is visible on every screen.
 *
 * @param viewModel The shared OmniChatbotViewModel
 * @param currentRoute The current navigation route (for contextual FAQs)
 * @param content The actual app content (NavHost)
 */
@Composable
fun OmniChatbotScaffold(
    viewModel: OmniChatbotViewModel,
    currentRoute: String?,
    content: @Composable () -> Unit
) {
    val isExpanded by viewModel.isExpanded.collectAsState()

    LaunchedEffect(currentRoute) {
        viewModel.updateRoute(currentRoute)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── App Content ──
        content()

        // ── FAB (visible when chatbot is collapsed) ──
        AnimatedVisibility(
            visible = !isExpanded,
            enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            OmniFAB(onClick = { viewModel.expand() })
        }

        // ── Bottom Sheet (visible when expanded) ──
        AnimatedVisibility(
            visible = isExpanded,
            enter = slideInVertically(tween(350)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(300)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            OmniChatSheet(viewModel = viewModel)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  FAB
// ═══════════════════════════════════════════════════════════

@Composable
private fun OmniFAB(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "fabGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    FloatingActionButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        containerColor = Color.Transparent,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentPurple,
                            AccentBlue.copy(alpha = glowAlpha)
                        )
                    ),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = "Open Omni-Chat",
                modifier = Modifier.size(26.dp),
                tint = Color.White
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  CHAT SHEET
// ═══════════════════════════════════════════════════════════

@Composable
private fun OmniChatSheet(viewModel: OmniChatbotViewModel) {
    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val currentRoute by viewModel.currentRoute.collectAsState()
    val listState = rememberLazyListState()

    val faqChips = remember(currentRoute) {
        ContextualFAQs.getChipsForRoute(currentRoute)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.6f), // 60% of screen
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = SheetBg,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Handle & Header ──
            ChatSheetHeader(onClose = { viewModel.collapse() })

            // ── FAQ Chips ──
            AnimatedVisibility(visible = messages.isEmpty()) {
                FAQChipRow(
                    chips = faqChips,
                    onChipClick = { viewModel.processPrompt(it.question) }
                )
            }

            // ── Messages ──
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyChatState()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    state = listState,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(
                            message = message,
                            onStopTask = { taskId -> viewModel.stopScheduledTask(taskId) }
                        )
                    }
                }
            }

            // ── Input Bar ──
            ChatInputBar(
                value = inputText,
                onValueChange = { viewModel.onInputChanged(it) },
                onSend = { viewModel.processPrompt() }
            )
        }
    }
}

// ─── Header ──────────────────────────────────────────────

@Composable
private fun ChatSheetHeader(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Drag handle
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.2f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gradient icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(UserBubbleGrad)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Omni-Chat",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Ask anything or automate tasks",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Minimize",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

// ─── FAQ Chips ───────────────────────────────────────────

@Composable
private fun FAQChipRow(chips: List<FAQChip>, onChipClick: (FAQChip) -> Unit) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            "Suggested questions:",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            chips.forEach { chip ->
                SuggestionChip(
                    onClick = { onChipClick(chip) },
                    label = {
                        Text(
                            chip.shortLabel,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = CardGlass,
                        labelColor = Color.White.copy(alpha = 0.8f)
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = Color.White.copy(alpha = 0.1f)
                    )
                )
            }
        }
    }
}

// ─── Empty State ────────────────────────────────────────

@Composable
private fun EmptyChatState() {
    val infiniteTransition = rememberInfiniteTransition(label = "empty")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .scale(pulseScale),
            tint = AccentPurple.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Ask me anything",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Automate tasks, get answers, or send commands",
            color = Color.White.copy(alpha = 0.3f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ─── Chat Bubble ────────────────────────────────────────

@Composable
private fun ChatBubble(
    message: OmniChatMessage,
    onStopTask: (String) -> Unit
) {
    val isUser = message.isUser

    // Entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Mode badge (for bot messages)
            if (!isUser) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
                ) {
                    Text(
                        text = message.mode.emoji,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = message.mode.label,
                        color = getModeColor(message.mode).copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Streaming indicator
                    if (message.isStreaming) {
                        Spacer(Modifier.width(6.dp))
                        val infiniteTransition = rememberInfiniteTransition(label = "dots")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.3f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(600),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "dotsAlpha"
                        )
                        Text(
                            "●●●",
                            color = getModeColor(message.mode).copy(alpha = alpha),
                            fontSize = 8.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }
            }

            // Bubble
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (isUser) Brush.horizontalGradient(UserBubbleGrad)
                        else Brush.horizontalGradient(
                            listOf(SystemBubbleBg, SystemBubbleBg)
                        )
                    )
                    .then(
                        if (!isUser) Modifier.background(
                            Brush.verticalGradient(
                                listOf(
                                    getModeColor(message.mode).copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            )
                        ) else Modifier
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(
                        text = message.text,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    // Action widget
                    message.actionWidget?.let { widget ->
                        Spacer(Modifier.height(8.dp))
                        when (widget) {
                            is ActionWidget.StopButton -> {
                                Button(
                                    onClick = { onStopTask(widget.taskId) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AccentRed.copy(alpha = 0.8f)
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text("Stop", fontSize = 12.sp)
                                }
                            }
                            is ActionWidget.Progress -> {
                                LinearProgressIndicator(
                                    progress = { widget.step.toFloat() / widget.total },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = AccentPurple,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${widget.description} (${widget.step}/${widget.total})",
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp
                                )
                            }
                            is ActionWidget.QuickReplies -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    widget.options.forEach { option ->
                                        OutlinedButton(
                                            onClick = { /* handle quick reply */ },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(
                                                horizontal = 10.dp,
                                                vertical = 4.dp
                                            ),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text(option, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Timestamp
            Text(
                text = formatTime(message.timestamp),
                color = Color.White.copy(alpha = 0.2f),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

// ─── Input Bar ──────────────────────────────────────────

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(InputBarBg)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    "Ask anything...",
                    color = Color.White.copy(alpha = 0.3f)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = AccentPurple,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            maxLines = 3,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
        )

        // Send button with pulse animation
        val hasText = value.isNotBlank()
        val sendScale by animateFloatAsState(
            targetValue = if (hasText) 1f else 0.7f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
            label = "sendScale"
        )

        IconButton(
            onClick = onSend,
            enabled = hasText,
            modifier = Modifier
                .scale(sendScale)
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (hasText) Brush.horizontalGradient(UserBubbleGrad)
                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = Color.White.copy(alpha = if (hasText) 1f else 0.3f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  UTILITIES
// ═══════════════════════════════════════════════════════════

private fun getModeColor(mode: ResponseMode): Color = when (mode) {
    ResponseMode.DIRECT -> AccentGreen
    ResponseMode.AGENT -> AccentPurple
    ResponseMode.DESKTOP -> AccentBlue
    ResponseMode.FAQ -> Color(0xFF5DADE2)
    ResponseMode.KNOWLEDGE -> AccentOrange
    ResponseMode.CHAT -> Color(0xFFAF7AC5)
    ResponseMode.SCHEDULED -> AccentOrange
    ResponseMode.SYSTEM -> Color.White
}

private fun formatTime(timestamp: Long): String {
    return DateFormat.format("hh:mm a", Date(timestamp)).toString()
}
