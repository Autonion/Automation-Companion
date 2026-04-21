package com.autonion.automationcompanion.features.omni_chatbot.ui

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalUriHandler
import android.util.Patterns
import com.autonion.automationcompanion.features.omni_chatbot.OmniChatbotViewModel
import com.autonion.automationcompanion.features.omni_chatbot.model.*
import com.autonion.automationcompanion.features.semantic_automation.ml.ServerConnectionStatus
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

/**
 * CompositionLocal that provides the walkthrough trigger to any feature screen.
 * Consume it with: val startWalkthrough = LocalStartWalkthrough.current
 */
val LocalStartWalkthrough = compositionLocalOf<(String) -> Unit> { {} }

/**
 * Global Omni-Chatbot overlay.
 * Place this in the app root so the FAB is visible on every screen.
 *
 * @param viewModel The shared OmniChatbotViewModel
 * @param currentRoute The current navigation route (for contextual FAQs)
 * @param onNavigate Callback to trigger navigation (consumed from ViewModel events)
 * @param content The actual app content (NavHost)
 */
@Composable
fun OmniChatbotScaffold(
    viewModel: OmniChatbotViewModel,
    currentRoute: String?,
    onNavigate: (String) -> Unit = {},
    content: @Composable () -> Unit
) {
    val isExpanded by viewModel.isExpanded.collectAsState()
    val walkthrough by viewModel.activeWalkthrough.collectAsState()
    val stepIndex by viewModel.currentStepIndex.collectAsState()
    val isWalkthroughActive = walkthrough != null

    // Routes where the FAB should be hidden (they have their own bottom input bars / FABs)
    val hideFabRoutes = setOf(
        "feature/semantic_automation",
        "feature/cross_device_automation",
        "feature/flow_builder",
        "feature/gesture_recording_playback",
        "feature/screen_understanding_using_on_device_ml",
        "feature/visual_trigger"
    )
    val shouldHideFab = hideFabRoutes.any { currentRoute?.contains(it) == true }
    val shouldShowFab = !isExpanded && !isWalkthroughActive && !shouldHideFab

    // Observe navigation events from ViewModel (for walkthrough navigation)
    LaunchedEffect(Unit) {
        viewModel.navigationEvent.collect { route ->
            onNavigate(route)
        }
    }

    LaunchedEffect(currentRoute) {
        viewModel.updateRoute(currentRoute)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── App Content (with walkthrough trigger available to all screens) ──
        CompositionLocalProvider(
            LocalStartWalkthrough provides { featureId -> viewModel.startWalkthrough(featureId) }
        ) {
            content()
        }

        // ── FAB (visible when chatbot is collapsed and not on blocked routes) ──
        AnimatedVisibility(
            visible = shouldShowFab,
            enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
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

        // ── Companion Floating Widget (visible during walkthroughs) ──
        if (isWalkthroughActive && !isExpanded) {
            walkthrough?.let { script ->
                CompanionFloatingBar(
                    walkthrough = script,
                    stepIndex = stepIndex,
                    onPrevious = { viewModel.previousWalkthroughStep() },
                    onNext = { viewModel.nextWalkthroughStep() },
                    onDismiss = { viewModel.dismissWalkthrough() }
                )
            }
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
    val showSettings by viewModel.showSettings.collectAsState()
    val listState = rememberLazyListState()

    val showFAQBrowser by viewModel.showFAQBrowser.collectAsState()
    val faqList by viewModel.faqList.collectAsState()

    val faqChips = remember(currentRoute) {
        ContextualFAQs.getChipsForRoute(currentRoute)
    }

    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 50.dp.toPx() }
    var dragOffsetY by remember { mutableStateOf(0f) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .graphicsLayer {
                translationY = dragOffsetY.coerceAtLeast(0f)
                alpha = 1f - (dragOffsetY.coerceAtLeast(0f) / (dragThresholdPx * 3)).coerceIn(0f, 0.3f)
            },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = SheetBg,
        tonalElevation = 8.dp,
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Drag Handle Zone ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragOffsetY = 0f },
                            onDragEnd = {
                                if (dragOffsetY > dragThresholdPx) {
                                    viewModel.collapse()
                                }
                                dragOffsetY = 0f
                            },
                            onDragCancel = { dragOffsetY = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                            }
                        )
                    }
            ) {
                ChatSheetHeader(
                    onClose = { viewModel.collapse() },
                    onSettingsClick = { viewModel.toggleSettings() },
                    onFAQBrowserClick = { viewModel.toggleFAQBrowser() },
                    connectionStatus = viewModel.llmConnectionStatus.collectAsState().value,
                    showSettings = showSettings,
                    showFAQBrowser = showFAQBrowser,
                    isDragging = dragOffsetY > 0f
                )
            }

            // ── LLM Settings Panel ──
            AnimatedVisibility(
                visible = showSettings,
                enter = expandVertically(tween(300)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(150))
            ) {
                LLMSettingsPanel(viewModel = viewModel)
            }

            // ── FAQ Browser Panel ──
            AnimatedVisibility(
                visible = showFAQBrowser,
                enter = expandVertically(tween(300)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(250)) + fadeOut(tween(150))
            ) {
                FAQBrowserUI(faqList = faqList, onFAQSelected = { viewModel.onFAQSelected(it) })
            }

            // ── FAQ Chips ──
            AnimatedVisibility(visible = messages.isEmpty() && !showSettings && !showFAQBrowser) {
                FAQChipRow(
                    chips = faqChips,
                    onChipClick = { viewModel.processPrompt(it.question) }
                )
            }

            // ── Messages ──
            if (messages.isEmpty() && !showSettings && !showFAQBrowser) {
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
                            onStopTask = { taskId -> viewModel.stopScheduledTask(taskId) },
                            onStartWalkthrough = { featureId -> viewModel.startWalkthrough(featureId) }
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
private fun ChatSheetHeader(
    onClose: () -> Unit,
    onSettingsClick: () -> Unit,
    onFAQBrowserClick: () -> Unit,
    connectionStatus: ServerConnectionStatus,
    showSettings: Boolean,
    showFAQBrowser: Boolean,
    isDragging: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Drag handle indicator
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = if (isDragging) 0.5f else 0.2f))
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

            // Connection status dot + Settings gear
            val statusColor = when (connectionStatus) {
                ServerConnectionStatus.CONNECTED -> AccentGreen
                ServerConnectionStatus.CONNECTING -> AccentOrange
                ServerConnectionStatus.DISCONNECTED -> AccentRed
            }

            // FAQ Browser button
            IconButton(onClick = onFAQBrowserClick) {
                Icon(
                    if (showFAQBrowser) Icons.Default.Close else Icons.Default.MenuBook,
                    contentDescription = "Browse FAQs",
                    tint = if (showFAQBrowser) Color.White.copy(alpha = 0.8f)
                           else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            }

            // Settings button with status indicator
            IconButton(onClick = onSettingsClick) {
                Box {
                    Icon(
                        if (showSettings) Icons.Default.Close else Icons.Default.Settings,
                        contentDescription = "LLM Settings",
                        tint = if (showSettings) Color.White.copy(alpha = 0.8f)
                               else Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(22.dp)
                    )
                    // Status dot overlay
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                }
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

// ─── LLM Settings Panel ─────────────────────────────────

@Composable
private fun LLMSettingsPanel(viewModel: OmniChatbotViewModel) {
    val connectionStatus by viewModel.llmConnectionStatus.collectAsState()
    val serverUrl by viewModel.llmServerUrl.collectAsState()
    val selectedModel by viewModel.llmSelectedModel.collectAsState()
    val availableModels by viewModel.llmAvailableModels.collectAsState()
    val inferenceMode by viewModel.inferenceMode.collectAsState()

    // Local IP input state — pre-fill from saved URL
    var ipInput by remember(serverUrl) {
        mutableStateOf(
            serverUrl
                .removePrefix("http://")
                .removePrefix("https://")
                .removeSuffix("/")
                .removeSuffix(":11434")
                .ifBlank { "" }
        )
    }
    var showModelDropdown by remember { mutableStateOf(false) }

    val isSLM = inferenceMode == com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.LOCAL_SLM
    val isLLM = inferenceMode == com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.SERVER_LLM

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF151829),
                        SheetBg
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Inference Mode Toggle ──
        Text(
            "AI Engine",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // SLM chip
            Surface(
                onClick = {
                    viewModel.setInferenceMode(
                        com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.LOCAL_SLM
                    )
                },
                color = if (isSLM) AccentPurple.copy(alpha = 0.25f) else CardGlass,
                shape = RoundedCornerShape(20.dp),
                border = if (isSLM) BorderStroke(1.dp, AccentPurple) else null,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = if (isSLM) AccentPurple else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "On-Device SLM",
                        color = if (isSLM) Color.White else Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = if (isSLM) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
            // LLM chip
            Surface(
                onClick = {
                    viewModel.setInferenceMode(
                        com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.SERVER_LLM
                    )
                },
                color = if (isLLM) AccentPurple.copy(alpha = 0.25f) else CardGlass,
                shape = RoundedCornerShape(20.dp),
                border = if (isLLM) BorderStroke(1.dp, AccentPurple) else null,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (isLLM) AccentPurple else Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Server LLM",
                        color = if (isLLM) Color.White else Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = if (isLLM) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        // ── SLM Mode Content ──
        AnimatedVisibility(
            visible = isSLM,
            enter = expandVertically(tween(200)) + fadeIn(),
            exit = shrinkVertically(tween(150)) + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentPurple.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Memory,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "On-Device AI (Gemma 2B)",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            "Runs locally on your phone. No server needed.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    "\uD83D\uDCA1 Import a .bin model from the SLM Hub in Settings → AI Model Manager to enable on-device inference.",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        // ── Server LLM Mode Content ──
        AnimatedVisibility(
            visible = isLLM,
            enter = expandVertically(tween(200)) + fadeIn(),
            exit = shrinkVertically(tween(150)) + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Status Banner
                val statusConfig = when (connectionStatus) {
                    ServerConnectionStatus.CONNECTED -> Triple(
                        "Connected", AccentGreen, Icons.Default.Cloud
                    )
                    ServerConnectionStatus.CONNECTING -> Triple(
                        "Connecting…", AccentOrange, Icons.Default.Wifi
                    )
                    ServerConnectionStatus.DISCONNECTED -> Triple(
                        "No AI Server", AccentRed, Icons.Default.CloudOff
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(statusConfig.second.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        statusConfig.third,
                        contentDescription = null,
                        tint = statusConfig.second,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            statusConfig.first,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        if (connectionStatus == ServerConnectionStatus.CONNECTED && selectedModel.isNotBlank()) {
                            Text(
                                "Model: $selectedModel",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }
                        if (connectionStatus == ServerConnectionStatus.DISCONNECTED) {
                            Text(
                                "Enter your Ollama server IP to connect",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (connectionStatus == ServerConnectionStatus.CONNECTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AccentOrange
                        )
                    }
                }

                // Server IP Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = ipInput,
                        onValueChange = { ipInput = it },
                        placeholder = {
                            Text("192.168.1.x", color = Color.White.copy(alpha = 0.25f))
                        },
                        modifier = Modifier
                            .weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CardGlass,
                            unfocusedContainerColor = CardGlass,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = AccentPurple,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )

                    Button(
                        onClick = { viewModel.connectToServer(ipInput) },
                        enabled = ipInput.isNotBlank() && connectionStatus != ServerConnectionStatus.CONNECTING,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPurple,
                            disabledContainerColor = AccentPurple.copy(alpha = 0.3f)
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text(
                            if (connectionStatus == ServerConnectionStatus.CONNECTED) "Reconnect" else "Connect",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Model Selector
                AnimatedVisibility(
                    visible = connectionStatus == ServerConnectionStatus.CONNECTED && availableModels.isNotEmpty(),
                    enter = expandVertically(tween(200)) + fadeIn(),
                    exit = shrinkVertically(tween(150)) + fadeOut()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "AI Model",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Box {
                            Surface(
                                onClick = { showModelDropdown = true },
                                color = CardGlass,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Psychology,
                                        contentDescription = null,
                                        tint = AccentPurple,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = selectedModel.ifBlank { "Select a model…" },
                                        color = if (selectedModel.isNotBlank()) Color.White
                                                else Color.White.copy(alpha = 0.4f),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false },
                                modifier = Modifier.background(CardGlass)
                            ) {
                                availableModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (model == selectedModel) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = AccentGreen,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(
                                                    model,
                                                    color = Color.White,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        },
                                        onClick = {
                                            viewModel.selectModel(model)
                                            showModelDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Help hint
                if (connectionStatus == ServerConnectionStatus.DISCONNECTED) {
                    Text(
                        "\uD83D\uDCA1 Run Ollama on your PC and enter its LAN IP above. Both devices must be on the same WiFi network.",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
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
    onStopTask: (String) -> Unit,
    onStartWalkthrough: (String) -> Unit
) {
    val isUser = message.isUser
    val uriHandler = LocalUriHandler.current

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
                    val annotatedText = buildAnnotatedString {
                        val matcher = Patterns.WEB_URL.matcher(message.text)
                        var lastIndex = 0
                        while (matcher.find()) {
                            val start = matcher.start()
                            val end = matcher.end()
                            val url = matcher.group()
                            append(message.text.substring(lastIndex, start))
                            pushStringAnnotation(tag = "URL", annotation = url)
                            withStyle(style = SpanStyle(
                                color = AccentBlue, 
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Bold
                            )) {
                                append(url)
                            }
                            pop()
                            lastIndex = end
                        }
                        append(message.text.substring(lastIndex))
                    }

                    ClickableText(
                        text = annotatedText,
                        style = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        onClick = { offset ->
                            annotatedText.getStringAnnotations("URL", offset, offset)
                                .firstOrNull()?.let { annotation ->
                                    var uri = annotation.item
                                    if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
                                        uri = "https://$uri"
                                    }
                                    try {
                                        uriHandler.openUri(uri)
                                    } catch (e: Exception) {
                                        // Ignore exception if URL is invalid
                                    }
                                }
                        }
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

            // Suggested Walkthrough Button
            message.suggestedWalkthroughId?.let { featureId ->
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onStartWalkthrough(featureId) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = AccentBlue
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.padding(start = 8.dp) // Align slightly with the bubble
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Start Walkthrough",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Take a Walkthrough", fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding()
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = value.startsWith("/") && value.length < 8,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("/android ", "/desktop ").forEach { cmd ->
                    Surface(
                        color = CardGlass,
                        shape = RoundedCornerShape(12.dp),
                        onClick = { onValueChange(cmd) }
                    ) {
                        Text(
                            text = cmd.trim(),
                            color = AccentPurple,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

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
            onClick = {
                focusManager.clearFocus()
                onSend()
            },
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
    ResponseMode.COMPANION -> Color(0xFF00BFA5)
    ResponseMode.SYSTEM -> Color.White
}

private fun formatTime(timestamp: Long): String {
    return DateFormat.format("hh:mm a", Date(timestamp)).toString()
}

// ─── FAQ Browser ─────────────────────────────────────────

@Composable
private fun FAQBrowserUI(
    faqList: List<com.autonion.automationcompanion.features.omni_chatbot.knowledge.FAQRepository.FAQ>,
    onFAQSelected: (com.autonion.automationcompanion.features.omni_chatbot.knowledge.FAQRepository.FAQ) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .background(SheetBg)
            .padding(horizontal = 16.dp)
    ) {
        val listState = rememberLazyListState()
        
        Text(
            "FAQ Library",
            color = Color.White.copy(alpha = 0.8f),
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(faqList, key = { it.question }) { faq ->
                Surface(
                    color = CardGlass,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onFAQSelected(faq) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = faq.question,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (faq.tags.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                faq.tags.take(3).forEach { tag ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentPurple.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            tag, 
                                            color = AccentPurple, 
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

