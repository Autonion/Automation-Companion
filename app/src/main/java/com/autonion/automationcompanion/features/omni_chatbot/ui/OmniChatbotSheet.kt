package com.autonion.automationcompanion.features.omni_chatbot.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.PointerEventPass
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
import com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiConnectionStatus
import com.autonion.automationcompanion.features.semantic_automation.ml.CLOUD_API_PROVIDERS
import com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiProvider
import com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiLLMEngine
import com.autonion.automationcompanion.features.semantic_automation.ml.ModelStorageManager
import com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode
import com.autonion.automationcompanion.features.semantic_automation.consent.CloudApiConsentManager
import com.autonion.automationcompanion.features.semantic_automation.ui.CloudApiDisclaimerDialog
import com.autonion.automationcompanion.ui.components.ChatHistoryPanel
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
        "feature/visual_trigger",
        "settings/exclusion",
        "onboarding"
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

    // Handle back button when the sheet is expanded
    BackHandler(enabled = isExpanded) {
        viewModel.collapse()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── App Content (with walkthrough trigger available to all screens) ──
        CompositionLocalProvider(
            LocalStartWalkthrough provides { featureId -> viewModel.startWalkthrough(featureId, fromOmniChat = false) }
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
    val isDark = isSystemInDarkTheme()
    val sheetBg = if (isDark) SheetBg else Color(0xFFF3F6FD)

    val messages by viewModel.messages.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val currentRoute by viewModel.currentRoute.collectAsState()
    val showSettings by viewModel.showSettings.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to the newest message when list grows or streaming text updates
    LaunchedEffect(messages.size, messages.firstOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

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
        color = sheetBg,
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
                    onNewChatClick = { viewModel.clearChat() },
                    onHistoryClick = { viewModel.toggleHistory() },
                    connectionStatus = viewModel.llmConnectionStatus.collectAsState().value,
                    cloudConnectionStatus = viewModel.cloudConnectionStatus.collectAsState().value,
                    inferenceMode = viewModel.inferenceMode.collectAsState().value,
                    showSettings = showSettings,
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

            // ── Main Content Area ──
            val isAIReady by viewModel.isAIReady.collectAsState()
            val showHistory by viewModel.showHistory.collectAsState()
            val chatHistorySessions by viewModel.chatHistorySessions.collectAsState()

            // ── Tab State ──
            var selectedTab by remember { mutableIntStateOf(1) } // Default to Chat tab

            // ── Tab Row ──
            OmniTabRow(selectedTab = selectedTab, onTabSelected = { selectedTab = it })

            // ── Tabbed Content ──
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(tween(250)) + slideInHorizontally(
                            tween(250),
                            initialOffsetX = { if (targetState > initialState) it / 4 else -it / 4 }
                        ) togetherWith fadeOut(tween(200))
                    },
                    label = "OmniTabContent",
                    modifier = Modifier.fillMaxSize()
                ) { tab ->
                    when (tab) {
                        0 -> {
                            // ── FAQ Tab ──
                            FAQBrowserUI(
                                faqList = faqList,
                                isAIReady = isAIReady,
                                onFAQSelected = { faq ->
                                    if (isAIReady) {
                                        viewModel.onFAQSelected(faq)
                                        selectedTab = 1 // Switch to Chat only when LLM available
                                    }
                                    // When !isAIReady, answer is shown inline (handled inside FAQBrowserUI)
                                }
                            )
                        }
                        1 -> {
                            // ── Chat Tab ──
                            Column(modifier = Modifier.fillMaxSize()) {
                                // ── FAQ Chips ──
                                AnimatedVisibility(visible = messages.isEmpty() && !showSettings && isAIReady) {
                                    FAQChipRow(
                                        chips = faqChips,
                                        onChipClick = { viewModel.processPrompt(it.question) }
                                    )
                                }

                                // ── Messages ──
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (messages.isEmpty() && !showSettings) {
                                        SmartWelcomeState(
                                            isAIReady = isAIReady,
                                            onOpenCloudApi = { viewModel.openSettingsWithMode(InferenceMode.CLOUD_API) },
                                            onOpenServer = { viewModel.openSettingsWithMode(InferenceMode.SERVER_LLM) },
                                            onOpenSLM = { viewModel.openSettingsWithMode(InferenceMode.LOCAL_SLM) },
                                            onBrowseFAQs = { selectedTab = 0 },
                                            onShowMeAround = {
                                                viewModel.processPrompt("Show me around the app")
                                            }
                                        )
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxSize()
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
                                                    onStartWalkthrough = { featureId -> viewModel.startWalkthrough(featureId, fromOmniChat = true) }
                                                )
                                            }
                                        }
                                    }
                                }

                                // ── Input Bar ──
                                ChatInputBar(
                                    value = inputText,
                                    onValueChange = { viewModel.onInputChanged(it) },
                                    onSend = { viewModel.processPrompt() },
                                    enabled = isAIReady
                                )
                            }
                        }
                    }
                }

                // ── History Panel Overlay ──
                androidx.compose.animation.AnimatedVisibility(
                    visible = showHistory,
                    enter = fadeIn(tween(200)) + slideInHorizontally(tween(300)) { -it },
                    exit = fadeOut(tween(200)) + slideOutHorizontally(tween(250)) { -it }
                ) {
                    ChatHistoryPanel(
                        sessions = chatHistorySessions,
                        onSessionClick = { session ->
                            viewModel.loadChatSession(session.sessionId)
                            viewModel.toggleHistory()
                        },
                        onDeleteSession = { viewModel.deleteSession(it.sessionId) },
                        onClose = { viewModel.toggleHistory() }
                    )
                }
            }
        }
    }
}

// ─── Tab Row ─────────────────────────────────────────────

private data class OmniTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
private fun OmniTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val isDark = isSystemInDarkTheme()
    val tabs = listOf(
        OmniTab("FAQ", Icons.Default.MenuBook),
        OmniTab("Chat", Icons.Default.Chat)
    )
    val containerBg = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.04f)
    val borderModifier = if (!isDark) Modifier.border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(12.dp)) else Modifier
    val unselectedColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.5f)
    val selectedColor = if (isDark) Color.White else Color.Black

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(containerBg)
            .then(borderModifier)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedTab
            val animatedAlpha by animateFloatAsState(
                if (isSelected) 1f else 0f,
                animationSpec = tween(200),
                label = "tab_bg_$index"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isSelected) {
                            if (isDark) {
                                Brush.horizontalGradient(
                                    listOf(AccentPurple.copy(alpha = 0.3f), AccentBlue.copy(alpha = 0.2f))
                                )
                            } else {
                                Brush.horizontalGradient(
                                    listOf(AccentPurple.copy(alpha = 0.85f), AccentBlue.copy(alpha = 0.75f))
                                )
                            }
                        } else Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.Transparent)
                        )
                    )
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.title,
                        tint = if (isSelected) selectedColor else unselectedColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tab.title,
                        color = if (isSelected) selectedColor else unselectedColor,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ─── Header ──────────────────────────────────────────────

@Composable
private fun ChatSheetHeader(
    onClose: () -> Unit,
    onSettingsClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onHistoryClick: () -> Unit,
    connectionStatus: ServerConnectionStatus,
    cloudConnectionStatus: CloudApiConnectionStatus = CloudApiConnectionStatus.DISCONNECTED,
    inferenceMode: InferenceMode = InferenceMode.SERVER_LLM,
    showSettings: Boolean,
    isDragging: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val dragHandleColor = if (isDark) Color.White.copy(alpha = if (isDragging) 0.5f else 0.2f) else Color.Black.copy(alpha = if (isDragging) 0.4f else 0.15f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.55f)
    val iconButtonTint = if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f)
    val newChatButtonTint = if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.75f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Drag handle indicator
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(dragHandleColor)
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
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Ask anything or automate tasks",
                    color = secondaryTextColor,
                    fontSize = 11.sp
                )
            }

            // Connection status dot + Settings gear
            val statusColor = when (inferenceMode) {
                InferenceMode.CLOUD_API -> when (cloudConnectionStatus) {
                    CloudApiConnectionStatus.CONNECTED -> AccentGreen
                    CloudApiConnectionStatus.CONNECTING -> AccentOrange
                    else -> AccentRed
                }
                InferenceMode.SERVER_LLM -> when (connectionStatus) {
                    ServerConnectionStatus.CONNECTED -> AccentGreen
                    ServerConnectionStatus.CONNECTING -> AccentOrange
                    ServerConnectionStatus.DISCONNECTED -> AccentRed
                }
                InferenceMode.LOCAL_SLM -> AccentPurple // SLM is always "local"
            }

            // New Chat button
            IconButton(onClick = onNewChatClick) {
                Icon(
                    Icons.Default.AddComment,
                    contentDescription = "New Chat",
                    tint = newChatButtonTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            // History button
            IconButton(onClick = onHistoryClick) {
                Icon(
                    Icons.Default.History,
                    contentDescription = "Chat History",
                    tint = iconButtonTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Settings button with status indicator
            IconButton(onClick = onSettingsClick) {
                Box {
                    Icon(
                        if (showSettings) Icons.Default.Close else Icons.Default.Settings,
                        contentDescription = "LLM Settings",
                        tint = if (showSettings) (if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.8f))
                               else iconButtonTint,
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
                    tint = iconButtonTint
                )
            }
        }
    }
}

// ─── LLM Settings Panel ─────────────────────────────────

@Composable
private fun LLMSettingsPanel(viewModel: OmniChatbotViewModel) {
    val isDark = isSystemInDarkTheme()
    val sheetBg = if (isDark) SheetBg else Color(0xFFF3F6FD)
    val panelBgStart = if (isDark) Color(0xFF151829) else Color(0xFFE2E7FA)
    val cardGlass = if (isDark) CardGlass else Color.White.copy(alpha = 0.75f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val textLabelColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.55f)

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

    val isSLM = inferenceMode == InferenceMode.LOCAL_SLM
    val isLLM = inferenceMode == InferenceMode.SERVER_LLM
    val isCloud = inferenceMode == InferenceMode.CLOUD_API

    val cloudStatus by viewModel.cloudConnectionStatus.collectAsState()
    val cloudSelectedProvider by viewModel.cloudSelectedProvider.collectAsState()
    var cloudApiKeyInput by remember(viewModel.cloudApiKey) { mutableStateOf(viewModel.cloudApiKey) }
    var cloudBaseUrlInput by remember(viewModel.cloudBaseUrl) { mutableStateOf(viewModel.cloudBaseUrl) }
    var cloudModelInput by remember(viewModel.cloudModelName) { mutableStateOf(viewModel.cloudModelName) }
    var showCloudProviderDropdown by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var showCloudConsentDialog by remember { mutableStateOf(false) }
    val cloudApiEngine = remember { CloudApiLLMEngine.getInstance(context) }
    var fetchedCloudModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingCloudModels by remember { mutableStateOf(false) }
    var showCloudModelDropdown by remember { mutableStateOf(false) }

    if (showCloudConsentDialog) {
        CloudApiDisclaimerDialog(
            onAccept = {
                CloudApiConsentManager.setConsent(context, true)
                showCloudConsentDialog = false
                viewModel.setInferenceMode(InferenceMode.CLOUD_API)
            },
            onDecline = {
                showCloudConsentDialog = false
            }
        )
    }

    LaunchedEffect(cloudStatus, cloudSelectedProvider, cloudBaseUrlInput, cloudApiKeyInput) {
        val isOllamaProvider = cloudSelectedProvider.id == "ollama"
        val isOllamaCustom = cloudSelectedProvider.id == "custom" && cloudBaseUrlInput.contains("ollama.com", ignoreCase = true)
        val isOllamaUrl = cloudSelectedProvider.baseUrl.contains("ollama.com", ignoreCase = true)

        if ((isOllamaProvider || isOllamaCustom || isOllamaUrl) && cloudApiKeyInput.isNotBlank()) {
            isFetchingCloudModels = true
            fetchedCloudModels = cloudApiEngine.getAvailableModels() ?: emptyList()
            isFetchingCloudModels = false
        } else if (cloudStatus == CloudApiConnectionStatus.CONNECTED &&
            (isOllamaCustom || isOllamaUrl)
        ) {
            isFetchingCloudModels = true
            fetchedCloudModels = cloudApiEngine.getAvailableModels() ?: emptyList()
            isFetchingCloudModels = false
        } else {
            fetchedCloudModels = emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        panelBgStart,
                        sheetBg
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Inference Mode Toggle ──
        Text(
            "AI Engine",
            color = textLabelColor,
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
                    viewModel.setInferenceMode(InferenceMode.LOCAL_SLM)
                },
                color = if (isSLM) AccentPurple.copy(alpha = 0.25f) else cardGlass,
                shape = RoundedCornerShape(20.dp),
                border = if (isSLM) BorderStroke(1.dp, AccentPurple) else if (!isDark) BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f)) else null,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = if (isSLM) AccentPurple else textColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "SLM",
                        color = if (isSLM) textColor else textColor.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = if (isSLM) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
            // LLM chip
            Surface(
                onClick = {
                    viewModel.setInferenceMode(InferenceMode.SERVER_LLM)
                },
                color = if (isLLM) AccentPurple.copy(alpha = 0.25f) else cardGlass,
                shape = RoundedCornerShape(20.dp),
                border = if (isLLM) BorderStroke(1.dp, AccentPurple) else if (!isDark) BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f)) else null,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = if (isLLM) AccentPurple else textColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Server",
                        color = if (isLLM) textColor else textColor.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = if (isLLM) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
            // Cloud API chip
            Surface(
                onClick = {
                    if (CloudApiConsentManager.hasConsent(context)) {
                        viewModel.setInferenceMode(InferenceMode.CLOUD_API)
                    } else {
                        showCloudConsentDialog = true
                    }
                },
                color = if (isCloud) AccentBlue.copy(alpha = 0.25f) else cardGlass,
                shape = RoundedCornerShape(20.dp),
                border = if (isCloud) BorderStroke(1.dp, AccentBlue) else if (!isDark) BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f)) else null,
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (isCloud) AccentBlue else textColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Cloud API",
                        color = if (isCloud) textColor else textColor.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = if (isCloud) FontWeight.SemiBold else FontWeight.Normal
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
                        .then(
                            if (!isDark) Modifier.border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.06f)), RoundedCornerShape(12.dp))
                            else Modifier
                        )
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
                        val modelManager = remember { ModelStorageManager(context) }
                        val activeModelName = remember(viewModel.inferenceMode) {
                            val path = modelManager.getActiveModelPath()
                            if (path != null) {
                                val fileName = java.io.File(path).nameWithoutExtension
                                fileName.replace("_", " ").replace("-", " ")
                                    .replaceFirstChar { it.uppercase() }
                            } else "Not Installed"
                        }
                        Text(
                            "On-Device AI ($activeModelName)",
                            color = textColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            "Runs locally on your phone. No server needed.",
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    "💡 Import a .gguf model from the SLM Hub in Settings → AI Model Manager to enable on-device inference.",
                    color = textColor.copy(alpha = 0.45f),
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
                        .then(
                            if (!isDark) Modifier.border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.06f)), RoundedCornerShape(12.dp))
                            else Modifier
                        )
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
                            color = textColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        if (connectionStatus == ServerConnectionStatus.CONNECTED && selectedModel.isNotBlank()) {
                            Text(
                                "Model: $selectedModel",
                                color = textColor.copy(alpha = 0.6f),
                                fontSize = 11.sp
                            )
                        }
                        if (connectionStatus == ServerConnectionStatus.DISCONNECTED) {
                            Text(
                                "Enter your Ollama server IP to connect",
                                color = textColor.copy(alpha = 0.5f),
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
                            Text("192.168.1.x", color = textColor.copy(alpha = 0.3f))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (!isDark) Modifier.border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f)), RoundedCornerShape(12.dp))
                                else Modifier
                            ),
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = cardGlass,
                            unfocusedContainerColor = cardGlass,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            cursorColor = AccentPurple,
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor
                        ),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                tint = textColor.copy(alpha = 0.45f),
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
                            color = textLabelColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Box {
                            Surface(
                                onClick = { showModelDropdown = true },
                                color = cardGlass,
                                shape = RoundedCornerShape(12.dp),
                                border = if (!isDark) BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f)) else null,
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
                                        color = if (selectedModel.isNotBlank()) textColor
                                                else textColor.copy(alpha = 0.4f),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = textColor.copy(alpha = 0.45f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false },
                                modifier = Modifier.background(cardGlass)
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
                                                    color = textColor,
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
                        "💡 Run Ollama on your PC and enter its LAN IP above. Both devices must be on the same WiFi network.",
                        color = textColor.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // ── Cloud API Mode Content ──
        AnimatedVisibility(
            visible = isCloud,
            enter = expandVertically(tween(200)) + fadeIn(),
            exit = shrinkVertically(tween(150)) + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Status Banner
                val cloudStatusConfig = when (cloudStatus) {
                    CloudApiConnectionStatus.CONNECTED -> Triple(
                        "Cloud API Connected", AccentGreen, Icons.Default.Cloud
                    )
                    CloudApiConnectionStatus.CONNECTING -> Triple(
                        "Connecting…", AccentOrange, Icons.Default.Wifi
                    )
                    CloudApiConnectionStatus.ERROR -> Triple(
                        "Connection Error", AccentRed, Icons.Default.CloudOff
                    )
                    CloudApiConnectionStatus.DISCONNECTED -> Triple(
                        "Not Configured", AccentRed, Icons.Default.CloudOff
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cloudStatusConfig.second.copy(alpha = 0.12f))
                        .then(
                            if (!isDark) Modifier.border(BorderStroke(1.dp, Color.Black.copy(alpha = 0.06f)), RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        cloudStatusConfig.third,
                        contentDescription = null,
                        tint = cloudStatusConfig.second,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            cloudStatusConfig.first,
                            color = textColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            if (cloudStatus == CloudApiConnectionStatus.CONNECTED)
                                "Using your configured Cloud API provider."
                            else
                                "Ready to connect to cloud models.",
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                }

                // Provider Dropdown
                Box {
                    Surface(
                        onClick = { showCloudProviderDropdown = true },
                        color = cardGlass,
                        shape = RoundedCornerShape(12.dp),
                        border = if (!isDark) BorderStroke(1.dp, Color.Black.copy(alpha = 0.08f)) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = AccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = cloudSelectedProvider.displayName,
                                color = textColor,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = textColor.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showCloudProviderDropdown,
                        onDismissRequest = { showCloudProviderDropdown = false },
                        modifier = Modifier.background(cardGlass)
                    ) {
                        CLOUD_API_PROVIDERS.forEach { provider ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (provider.id == cloudSelectedProvider.id) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = AccentGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(
                                            provider.displayName,
                                            color = textColor,
                                            fontSize = 13.sp
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.setCloudProvider(provider)
                                    cloudBaseUrlInput = provider.baseUrl
                                    cloudModelInput = provider.defaultModel
                                    showCloudProviderDropdown = false
                                }
                            )
                        }
                    }
                }

                // Custom Base URL input (only if 'custom')
                if (cloudSelectedProvider.id == "custom") {
                    OutlinedTextField(
                        value = cloudBaseUrlInput,
                        onValueChange = { cloudBaseUrlInput = it },
                        placeholder = { Text("Custom Base URL", color = textColor.copy(alpha = 0.3f), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = textColor.copy(alpha = 0.15f),
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // API Key Input
                    var obscureKey by remember { mutableStateOf(true) }
                    OutlinedTextField(
                        value = cloudApiKeyInput,
                        onValueChange = { cloudApiKeyInput = it },
                        placeholder = { Text("API Key", color = textColor.copy(alpha = 0.3f), fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = if (obscureKey) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        trailingIcon = {
                            IconButton(onClick = { obscureKey = !obscureKey }) {
                                Icon(
                                    if (obscureKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = null,
                                    tint = textColor.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentBlue,
                            unfocusedBorderColor = textColor.copy(alpha = 0.15f),
                            focusedTextColor = textColor,
                            unfocusedTextColor = textColor
                        )
                    )

                    // Model Name Input
                    if (isFetchingCloudModels) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(0.6f)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Fetching models...", fontSize = 13.sp, color = textColor.copy(alpha=0.7f))
                        }
                    } else if (fetchedCloudModels.isNotEmpty() || cloudSelectedProvider.suggestedModels.isNotEmpty()) {
                        val modelsToShow = if (fetchedCloudModels.isNotEmpty()) fetchedCloudModels else cloudSelectedProvider.suggestedModels
                        Box(modifier = Modifier.weight(0.6f)) {
                            Surface(
                                onClick = { showCloudModelDropdown = true },
                                color = Color.Transparent,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, textColor.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = cloudModelInput.ifBlank { "Model" },
                                        color = if (cloudModelInput.isNotBlank()) textColor else textColor.copy(alpha = 0.3f),
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = textColor.copy(alpha = 0.4f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showCloudModelDropdown,
                                onDismissRequest = { showCloudModelDropdown = false },
                                modifier = Modifier.background(cardGlass)
                            ) {
                                modelsToShow.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (model == cloudModelInput) {
                                                    Icon(
                                                        Icons.Default.CheckCircle,
                                                        contentDescription = null,
                                                        tint = AccentGreen,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Text(model, color = textColor, fontSize = 13.sp)
                                            }
                                        },
                                        onClick = {
                                            cloudModelInput = model
                                            showCloudModelDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = cloudModelInput,
                            onValueChange = { cloudModelInput = it },
                            placeholder = { Text("Model", color = textColor.copy(alpha = 0.3f), fontSize = 13.sp) },
                            modifier = Modifier.weight(0.6f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = textColor.copy(alpha = 0.15f),
                                focusedTextColor = textColor,
                                unfocusedTextColor = textColor
                            )
                        )
                    }
                }

                Button(
                    onClick = { viewModel.saveAndConnectCloudApi(cloudApiKeyInput, cloudBaseUrlInput, cloudModelInput) },
                    enabled = cloudApiKeyInput.isNotBlank() && cloudStatus != CloudApiConnectionStatus.CONNECTING,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentBlue,
                        disabledContainerColor = AccentBlue.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        if (cloudStatus == CloudApiConnectionStatus.CONNECTED) "Save & Reconnect" else "Save & Connect",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ─── FAQ Chips ───────────────────────────────────────────

@Composable
private fun FAQChipRow(chips: List<FAQChip>, onChipClick: (FAQChip) -> Unit) {
    val isDark = isSystemInDarkTheme()
    val textLabelColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.45f)
    val cardGlass = if (isDark) CardGlass else Color.White.copy(alpha = 0.75f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val cardBorderColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.08f)

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            "Suggested questions:",
            color = textLabelColor,
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
                        containerColor = cardGlass,
                        labelColor = textColor.copy(alpha = 0.8f)
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = cardBorderColor
                    )
                )
            }
        }
    }
}


@Composable
private fun SmartWelcomeState(
    isAIReady: Boolean,
    onOpenCloudApi: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenSLM: () -> Unit,
    onBrowseFAQs: () -> Unit,
    onShowMeAround: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.55f)
    val textPromptColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.65f)
    val textSubPromptColor = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.45f)
    val cardGlass = if (isDark) CardGlass else Color.White.copy(alpha = 0.75f)
    val cardBorder = if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.08f)
    val borderStroke = if (isDark) null else BorderStroke(1.dp, cardBorder)

    val infiniteTransition = rememberInfiniteTransition(label = "welcome")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .scale(pulseScale),
            tint = AccentPurple.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(12.dp))

        if (isAIReady) {
            // ── AI Connected: Standard prompt ──
            Text(
                "Ask me anything",
                color = textPromptColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Automate tasks, get answers, or send commands",
                color = textSubPromptColor,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        } else {
            // ── AI Not Connected: Smart Welcome ──
            Text(
                "👋 Welcome to Omni-Chat!",
                color = textColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "I can answer questions, guide you through features, and run automations.",
                color = secondaryTextColor,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(20.dp))

            // ── AI Setup Section ──
            Surface(
                color = cardGlass,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                border = borderStroke,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "⚡ Connect AI for full power",
                        color = textColor.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    WelcomeSetupChip(
                        emoji = "☁️",
                        label = "Cloud API",
                        hint = "OpenAI, Groq, etc.",
                        color = AccentBlue,
                        onClick = onOpenCloudApi
                    )
                    WelcomeSetupChip(
                        emoji = "🖥️",
                        label = "Ollama Server",
                        hint = "Run on your PC",
                        color = AccentGreen,
                        onClick = onOpenServer
                    )
                    WelcomeSetupChip(
                        emoji = "📱",
                        label = "On-Device SLM",
                        hint = "Runs locally",
                        color = AccentPurple,
                        onClick = onOpenSLM
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                "Even without AI, you can browse FAQs and explore features!",
                color = textColor.copy(alpha = 0.45f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            // ── Quick Action Chips ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = onShowMeAround,
                    color = AccentPurple.copy(alpha = if (isDark) 0.15f else 0.1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentPurple.copy(alpha = 0.3f))
                ) {
                    Text(
                        "📖 Show me around",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = textColor.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                }
                Surface(
                    onClick = onBrowseFAQs,
                    color = AccentBlue.copy(alpha = if (isDark) 0.15f else 0.1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentBlue.copy(alpha = 0.3f))
                ) {
                    Text(
                        "❓ Browse FAQs",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = textColor.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/** Individual setup option chip used in the smart welcome state. */
@Composable
private fun WelcomeSetupChip(
    emoji: String,
    label: String,
    hint: String,
    color: Color,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.5f)
    val borderStroke = if (isDark) null else BorderStroke(1.dp, Color.Black.copy(alpha = 0.06f))

    Surface(
        onClick = onClick,
        color = color.copy(alpha = if (isDark) 0.1f else 0.08f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        border = borderStroke,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                hint,
                color = secondaryTextColor,
                fontSize = 11.sp
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp)
            )
        }
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

    val isDark = isSystemInDarkTheme()
    val bubbleTextColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val systemBubbleBg = if (isDark) SystemBubbleBg else Color(0xFFE8ECF5)
    val timestampColor = if (isDark) Color.White.copy(alpha = 0.2f) else Color.Black.copy(alpha = 0.3f)
    val cardBorderColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)

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
                            listOf(systemBubbleBg, systemBubbleBg)
                        )
                    )
                    .then(
                        if (!isUser) Modifier.border(
                            1.dp,
                            cardBorderColor,
                            RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = 4.dp,
                                bottomEnd = 16.dp
                            )
                        ) else Modifier
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
                            color = bubbleTextColor,
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
                                    trackColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.08f)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${widget.description} (${widget.step}/${widget.total})",
                                    color = bubbleTextColor.copy(alpha = 0.5f),
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
                color = timestampColor,
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
    onSend: () -> Unit,
    enabled: Boolean = true
) {
    val isDark = isSystemInDarkTheme()
    val inputBarBg = if (isDark) InputBarBg else Color.White.copy(alpha = 0.88f)
    val cardGlass = if (isDark) CardGlass else Color.White.copy(alpha = 0.75f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.45f)
    val borderModifier = if (enabled) {
        if (!isDark) Modifier.border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(28.dp)) else Modifier
    } else {
        Modifier.border(1.dp, if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
    }

    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .navigationBarsPadding()
            .imePadding()
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = enabled && value.startsWith("/") && value.length < 8,
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
                        color = cardGlass,
                        shape = RoundedCornerShape(12.dp),
                        border = if (!isDark) BorderStroke(1.dp, Color.Black.copy(alpha = 0.06f)) else null,
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
                .background(if (enabled) inputBarBg else inputBarBg.copy(alpha = 0.35f))
                .then(borderModifier)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (enabled) "Ask anything..." else "Connect AI to start chatting...",
                        color = secondaryTextColor
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = AccentPurple,
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    disabledTextColor = textColor.copy(alpha = 0.4f),
                    disabledContainerColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                maxLines = 3,
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
            )

        // Send button with pulse animation
        val hasText = value.isNotBlank()
        val sendScale by animateFloatAsState(
            targetValue = if (enabled && hasText) 1f else 0.7f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
            label = "sendScale"
        )

        IconButton(
            onClick = {
                focusManager.clearFocus()
                onSend()
            },
            enabled = enabled && hasText,
            modifier = Modifier
                .scale(sendScale)
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (enabled && hasText) Brush.horizontalGradient(UserBubbleGrad)
                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (isDark) Color.White.copy(alpha = if (enabled && hasText) 1f else 0.15f) else Color.White.copy(alpha = if (enabled && hasText) 1f else 0.4f),
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
    isAIReady: Boolean,
    onFAQSelected: (com.autonion.automationcompanion.features.omni_chatbot.knowledge.FAQRepository.FAQ) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val sheetBg = if (isDark) SheetBg else Color(0xFFF3F6FD)
    val cardGlass = if (isDark) CardGlass else Color.White.copy(alpha = 0.75f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryTextColor = if (isDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.5f)
    val outlineBorderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.12f)
    val focusContainerColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val unfocusContainerColor = if (isDark) Color.White.copy(alpha = 0.03f) else Color.Black.copy(alpha = 0.015f)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.08f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .background(sheetBg)
            .padding(horizontal = 16.dp)
    ) {
        val listState = rememberLazyListState()
        var searchQuery by remember { mutableStateOf("") }
        var expandedFaqId by remember { mutableStateOf<String?>(null) }

        Text(
            "FAQ Library",
            color = textColor.copy(alpha = 0.85f),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
        )

        // ── Search Bar ──
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text("Search FAQs...", color = secondaryTextColor, fontSize = 14.sp)
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = secondaryTextColor,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = secondaryTextColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = textColor,
                unfocusedTextColor = textColor,
                cursorColor = AccentPurple,
                focusedBorderColor = AccentPurple.copy(alpha = 0.5f),
                unfocusedBorderColor = outlineBorderColor,
                focusedContainerColor = focusContainerColor,
                unfocusedContainerColor = unfocusContainerColor,
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
        )

        // ── Filter FAQs ──
        val filteredFaqs = remember(searchQuery, faqList) {
            if (searchQuery.isBlank()) faqList
            else {
                val q = searchQuery.lowercase()
                faqList.filter { faq ->
                    faq.question.lowercase().contains(q) ||
                    faq.answer.lowercase().contains(q) ||
                    faq.tags.any { it.lowercase().contains(q) }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (filteredFaqs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                tint = secondaryTextColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "No FAQs match \"$searchQuery\"",
                                color = secondaryTextColor,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
            items(filteredFaqs, key = { it.question }) { faq ->
                val isExpanded = expandedFaqId == faq.question
                Surface(
                    color = cardGlass,
                    shape = RoundedCornerShape(12.dp),
                    border = if (!isDark) BorderStroke(1.dp, outlineBorderColor) else null,
                    modifier = Modifier.fillMaxWidth().animateItem(),
                    onClick = {
                        // Always toggle inline answer
                        expandedFaqId = if (isExpanded) null else faq.question
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = faq.question,
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = secondaryTextColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
                        // ── Inline Answer (always available) ──
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = expandVertically(tween(250)) + fadeIn(tween(200)),
                            exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(color = dividerColor)
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = faq.answer,
                                    color = textColor.copy(alpha = 0.75f),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

