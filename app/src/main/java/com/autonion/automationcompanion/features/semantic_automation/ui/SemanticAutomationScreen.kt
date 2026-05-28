package com.autonion.automationcompanion.features.semantic_automation.ui

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationService
import com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus
import com.autonion.automationcompanion.ui.components.AuroraBackground
import com.autonion.automationcompanion.ui.components.ChatHistoryPanel
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatSessionEntity
import com.autonion.automationcompanion.features.omni_chatbot.data.db.OmniChatMessageEntity
import com.autonion.automationcompanion.features.system_context_automation.location.data.db.AppDatabase
import androidx.compose.material.icons.outlined.Info
import com.autonion.automationcompanion.features.omni_chatbot.ui.LocalStartWalkthrough
import com.autonion.automationcompanion.ui.theme.*
import com.autonion.automationcompanion.ui.rememberScreenWidthDp
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.ui.components.FeatureTipSheet
import com.autonion.automationcompanion.ui.components.YouTubeTutorials
import androidx.compose.material.icons.filled.PlayCircle
import kotlinx.coroutines.launch
import java.util.*

// ─── Color Palette ────────────────────────────────────────────
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)
private val UserBubbleBg = Color(0xFF7C4DFF)
private val SystemBubbleBg = Color(0xFF1E2030)
private val CardGlass = Color(0xFF1A1D2E).copy(alpha = 0.55f)
private val InputBarBg = Color(0xFF1A1D2E).copy(alpha = 0.7f)

data class SemanticMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isInteractivePrompt: Boolean = false,
    val promptOptions: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticAutomationScreen(
    onBack: () -> Unit,
    onOpenModelManager: () -> Unit = {},
    onStart: (String) -> Unit,
    onStop: () -> Unit
) {
    var command by remember { mutableStateOf("") }
    var showLiveStatus by remember { mutableStateOf(true) }
    val startWalkthrough = LocalStartWalkthrough.current

    val isDark = isSystemInDarkTheme()
    val cardGlassColor = if (isDark) CardGlass else Color.White.copy(alpha = 0.7f)
    val inputBarBgColor = if (isDark) InputBarBg else Color.White.copy(alpha = 0.85f)
    val headerTextColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val headerSubTextColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1A1C1E).copy(alpha = 0.65f)

    // Chat state
    val messages = remember { mutableStateListOf<SemanticMessage>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── First-visit Feature Tip ──
    val onboardingPrefs = remember { OnboardingPreferences.getInstance(context) }
    var showTip by remember { mutableStateOf(!onboardingPrefs.hasTipBeenSeen("semantic_automation")) }

    if (showTip) {
        FeatureTipSheet(
            title = "Semantic AI Agent",
            tips = listOf(
                "Connect an **AI model** first via ⚙ Settings in the top bar",
                "Type a natural language command like **'open YouTube and play music'**",
                "The AI agent will **take control** of your screen to execute the task"
            ),
            icon = Icons.Default.AutoAwesome,
            iconColor = Color(0xFFFF9800),
            youtubeLink = YouTubeTutorials.SEMANTIC_AUTOMATION,
            onDismiss = { onboardingPrefs.markTipSeen("semantic_automation"); showTip = false },
            onShowWalkthrough = { showTip = false; startWalkthrough("semantic_automation") }
        )
    }

    // ─── Chat History Persistence ─────────────────────────────
    val chatDao = remember { AppDatabase.get(context).omniChatDao() }
    var chatSessionId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var showHistory by remember { mutableStateOf(false) }
    val chatHistorySessions by chatDao.getSessionsByModule("semantic").collectAsState(initial = emptyList())

    // Helper to persist messages
    fun persistMessage(msg: SemanticMessage) {
        scope.launch {
            val sessionEntity = OmniChatSessionEntity(
                sessionId = chatSessionId,
                title = messages.lastOrNull { it.isUser }?.text?.take(30) ?: "New Chat",
                timestamp = System.currentTimeMillis(),
                previewText = msg.text.take(50),
                module = "semantic"
            )
            chatDao.upsertSession(sessionEntity)

            val messageEntity = OmniChatMessageEntity(
                messageId = msg.id,
                sessionId = chatSessionId,
                text = msg.text,
                isUser = msg.isUser,
                mode = "DIRECT",
                timestamp = msg.timestamp,
                actionWidgetJson = null,
                suggestedWalkthroughId = null
            )
            chatDao.insertMessage(messageEntity)
        }
    }
    LaunchedEffect(messages.size, messages.firstOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    // Observe service state
    val engine by SemanticAutomationService.activeEngine.collectAsState()

    val status by remember(engine) { engine?.status ?: kotlinx.coroutines.flow.MutableStateFlow(AutomationStatus.IDLE) }.collectAsState()
    val goal by remember(engine) { engine?.currentGoal ?: kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsState()
    val loopCount by remember(engine) { engine?.loopCount ?: kotlinx.coroutines.flow.MutableStateFlow(0) }.collectAsState()
    val lastAction by remember(engine) { engine?.lastActionDescription ?: kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsState()

    val userPromptMessage by remember(engine) { engine?.userPromptMessage ?: kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsState()
    val userPromptOptions by remember(engine) { engine?.userPromptOptions ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }.collectAsState()

    val isActive = status !in listOf(
        AutomationStatus.IDLE,
        AutomationStatus.AWAITING_USER_INPUT, // Paused, so not "active" in the sense of running
        AutomationStatus.COMPLETED,
        AutomationStatus.FAILED,
        AutomationStatus.CANCELLED
    )

    // Pulse animation for active state
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Track state changes to add system messages
    LaunchedEffect(status) {
        when (status) {
            AutomationStatus.COMPLETED -> {
                messages.add(
                    0,
                    SemanticMessage(
                        id = UUID.randomUUID().toString(),
                        text = "Task completed successfully.",
                        isUser = false
                    ).also { persistMessage(it) }
                )
            }
            AutomationStatus.FAILED -> {
                val reason = if (!lastAction.isNullOrBlank()) "\n$lastAction" else ""
                messages.add(
                    0,
                    SemanticMessage(
                        id = UUID.randomUUID().toString(),
                        text = "Task failed to complete.$reason",
                        isUser = false
                    ).also { persistMessage(it) }
                )
            }
            AutomationStatus.CANCELLED -> {
                messages.add(
                    0,
                    SemanticMessage(
                        id = UUID.randomUUID().toString(),
                        text = "Task cancelled.",
                        isUser = false
                    ).also { persistMessage(it) }
                )
            }
            AutomationStatus.AWAITING_USER_INPUT -> {
                if (userPromptMessage != null) {
                    messages.add(
                        0,
                        SemanticMessage(
                            id = UUID.randomUUID().toString(),
                            text = userPromptMessage!!,
                            isUser = false,
                            isInteractivePrompt = true,
                            promptOptions = userPromptOptions
                        ).also { persistMessage(it) }
                    )
                }
            }
            else -> {}
        }
    }

    AuroraBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Semantic AI Agent", color = headerTextColor, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = headerTextColor)
                        }
                    },
                    actions = {
                        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                        IconButton(onClick = { uriHandler.openUri(YouTubeTutorials.SEMANTIC_AUTOMATION) }) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Watch Video Tutorial", tint = headerTextColor)
                        }
                        IconButton(onClick = { startWalkthrough("semantic_automation") }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Take a Walkthrough", tint = headerTextColor)
                        }
                        IconButton(onClick = { showLiveStatus = !showLiveStatus }) {
                            Icon(
                                if (showLiveStatus) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle Live Status",
                                tint = if (isActive && showLiveStatus) AccentPurple else headerTextColor
                            )
                        }
                        IconButton(onClick = onOpenModelManager) {
                            Icon(Icons.Default.Settings, contentDescription = "SLM Hub", tint = headerTextColor)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = headerTextColor
                    )
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            // ─── Connection State for Overlay ────────────
            val context = LocalContext.current
            val llmEngine = remember { com.autonion.automationcompanion.features.semantic_automation.ml.LocalServerLLMEngine.getInstance(context) }
            val llmConnectionStatus by llmEngine.connectionStatus.collectAsState()
            val extensionBridge = remember { com.autonion.automationcompanion.features.semantic_automation.core.ExtensionBridgeServer.getInstance(context) }
            val isExtensionConnected by extensionBridge.isExtensionConnected.collectAsState()

            val localInferenceMode = remember {
                val prefs = context.getSharedPreferences("inference_prefs", android.content.Context.MODE_PRIVATE)
                val mode = prefs.getString("mode", com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.SERVER_LLM.name)
                com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.valueOf(mode!!)
            }

            val cloudApiEngine = remember { com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiLLMEngine.getInstance(context) }
            val cloudConnectionStatus by cloudApiEngine.connectionStatus.collectAsState()

            val isAIReady = when (localInferenceMode) {
                com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.SERVER_LLM ->
                    llmConnectionStatus == com.autonion.automationcompanion.features.semantic_automation.ml.ServerConnectionStatus.CONNECTED
                com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.CLOUD_API ->
                    cloudConnectionStatus == com.autonion.automationcompanion.features.semantic_automation.ml.CloudApiConnectionStatus.CONNECTED
                com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.LOCAL_SLM ->
                    true // SLM runs locally, always ready
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .then(if (!isAIReady) Modifier.blur(12.dp) else Modifier)
                ) {
                // ─── Chat Header (New Chat + History) ────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        messages.clear()
                        chatSessionId = UUID.randomUUID().toString()
                        showHistory = false
                    }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "New Chat",
                            tint = headerTextColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("New Chat", color = headerTextColor.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { showHistory = !showHistory }) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "History",
                            tint = headerTextColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("History", color = headerTextColor.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }

                // ─── Chat Messages ──────────────────────
                if (messages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyChatState(isDark, headerTextColor, headerSubTextColor)
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
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            ChatBubble(
                                message = message,
                                isDark = isDark,
                                onOptionSelected = { option ->
                                    // Add the user's choice to chat
                                    messages.add(
                                        0,
                                        SemanticMessage(
                                            id = UUID.randomUUID().toString(),
                                            text = option,
                                            isUser = true
                                        )
                                    )
                                    engine?.resumeWithUserChoice(option)
                                }
                            )
                        }
                    }
                }

                // ─── Floating Live Status Card ────────────────
                AnimatedVisibility(
                    visible = showLiveStatus && (isActive || status == AutomationStatus.COMPLETED || status == AutomationStatus.FAILED)
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .border(1.dp, if (isDark) Color.White.copy(alpha = 0.1f) else Color.Black.copy(alpha = 0.08f), RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (status) {
                                AutomationStatus.COMPLETED -> AccentGreen.copy(alpha = 0.2f)
                                AutomationStatus.FAILED -> AccentRed.copy(alpha = 0.2f)
                                else -> cardGlassColor
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isActive) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .alpha(pulseAlpha),
                                        strokeWidth = 2.dp,
                                        color = AccentPurple
                                    )
                                } else {
                                    Icon(
                                        when (status) {
                                            AutomationStatus.COMPLETED -> Icons.Default.CheckCircle
                                            AutomationStatus.FAILED -> Icons.Default.Error
                                            else -> Icons.Default.Info
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = when (status) {
                                            AutomationStatus.COMPLETED -> AccentGreen
                                            AutomationStatus.FAILED -> AccentRed
                                            else -> headerTextColor.copy(alpha = 0.7f)
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    statusLabel(status),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = headerTextColor
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                if (isActive) {
                                    IconButton(
                                        onClick = onStop,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = "Stop Agent", tint = AccentRed)
                                    }
                                }
                            }

                            goal?.let { g ->
                                Text(
                                    "Goal: ${g.rawCommand}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = headerSubTextColor
                                )
                                Text(
                                    "Task: ${g.task} | App: ${g.targetApp ?: "—"} | Query: ${g.query ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = headerSubTextColor
                                )
                            }

                            if (loopCount > 0) {
                                Text(
                                    "Loop iteration: $loopCount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = headerSubTextColor
                                )
                            }

                            lastAction?.let { desc ->
                                Text(
                                    "Last: $desc",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = headerTextColor
                                )
                            }
                        }
                    }
                }

                // ─── Input Bar ──────────────────────
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ChatInputBar(
                        value = command,
                        onValueChange = { if (!isActive) command = it },
                        onSend = {
                            if (command.isNotBlank() && !isActive) {
                                messages.add(
                                    0,
                                    SemanticMessage(
                                        id = UUID.randomUUID().toString(),
                                        text = command,
                                        isUser = true
                                    ).also { persistMessage(it) }
                                )
                                if (status == AutomationStatus.AWAITING_USER_INPUT) {
                                    engine?.resumeWithUserChoice(command.trim())
                                } else {
                                    onStart(command)
                                }
                                command = ""
                            }
                        },
                        isActive = isActive,
                        isDark = isDark
                    )
                }
                }

                // ─── Connection Required Overlay ──────────
                if (!isAIReady) {
                    val overlayMessage = when (localInferenceMode) {
                        com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.CLOUD_API ->
                            "Configure your Cloud API key and select a model to use Semantic Automation."
                        com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.SERVER_LLM ->
                            "Connect to a Server LLM to use Semantic Automation."
                        else ->
                            "Configure a Local SLM or connect to a Server LLM to use Semantic Automation."
                    }
                    val overlaySteps = when (localInferenceMode) {
                        com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationEngine.InferenceMode.CLOUD_API -> listOf(
                            "Tap the ⚙️ icon to open AI Engine Hub.",
                            "Select 'Cloud API' inference mode.",
                            "Choose a provider and enter your API key.",
                            "Tap 'Save & Test Connection'."
                        )
                        else -> listOf(
                            "Tap the ⚙️ icon to open AI Engine Hub.",
                            "Choose 'Server LLM', 'Cloud API', or 'On-Device SLM'.",
                            "Configure the connection and test it."
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        com.autonion.automationcompanion.ui.components.ConnectionRequiredOverlay(
                            message = overlayMessage,
                            steps = overlaySteps,
                            optionalChip = if (!isExtensionConnected) "Lemur browser extension not connected" else null
                        )
                    }
                }

                // ─── History Panel Overlay ────────────────────
                AnimatedVisibility(
                    visible = showHistory,
                    enter = fadeIn(tween(200)) + slideInHorizontally(tween(300)) { -it },
                    exit = fadeOut(tween(200)) + slideOutHorizontally(tween(250)) { -it }
                ) {
                    ChatHistoryPanel(
                        sessions = chatHistorySessions,
                        onSessionClick = { session ->
                            scope.launch {
                                val dbMessages = chatDao.getMessagesForSession(session.sessionId)
                                chatSessionId = session.sessionId
                                messages.clear()
                                messages.addAll(
                                    dbMessages.map { entity ->
                                        SemanticMessage(
                                            id = entity.messageId,
                                            text = entity.text,
                                            isUser = entity.isUser,
                                            timestamp = entity.timestamp
                                        )
                                    }.reversed()
                                )
                                showHistory = false
                            }
                        },
                        onDeleteSession = { session ->
                            scope.launch { chatDao.deleteSession(session.sessionId) }
                        },
                        onClose = { showHistory = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyChatState(isDark: Boolean, headerTextColor: Color, headerSubTextColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .scale(pulseScale),
            tint = AccentPurple.copy(alpha = 0.6f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Start a conversation",
            color = headerTextColor.copy(alpha = 0.6f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Describe what you want to automate",
            color = headerSubTextColor,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ChatBubble(message: SemanticMessage, isDark: Boolean, onOptionSelected: (String) -> Unit) {
    val isUser = message.isUser
    val screenWidth = rememberScreenWidthDp()
    val bubbleMaxWidth = (screenWidth * 0.65f).coerceAtMost(480.dp)

    val systemBubbleBg = if (isDark) SystemBubbleBg else Color(0xFFE8ECF5)
    val systemBubbleText = if (isDark) Color.White else Color(0xFF1A1C1E)

    // Entrance animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (isUser) Brush.horizontalGradient(
                            listOf(AccentPurple, AccentBlue)
                        ) else Brush.horizontalGradient(
                            listOf(systemBubbleBg, systemBubbleBg)
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = message.text,
                        color = if (isUser) Color.White else systemBubbleText,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )

                    if (message.isInteractivePrompt && message.promptOptions.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            message.promptOptions.forEach { option ->
                                Button(
                                    onClick = { onOptionSelected(option) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f),
                                        contentColor = if (isDark) Color.White else Color(0xFF1A1C1E)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(option, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Timestamp
            Text(
                text = formatTime(message.timestamp),
                color = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.35f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    isActive: Boolean,
    isDark: Boolean
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .then(
                if (!isDark) Modifier.border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
                else Modifier
            )
            .background(if (isDark) InputBarBg else Color.White.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = !isActive,
            placeholder = {
                Text(
                    if (isActive) "Agent is running..." else "Ask something...",
                    color = if (isDark) Color.White.copy(alpha = 0.35f) else Color(0xFF1A1C1E).copy(alpha = 0.45f)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = AccentPurple,
                focusedTextColor = if (isDark) Color.White else Color(0xFF1A1C1E),
                unfocusedTextColor = if (isDark) Color.White else Color(0xFF1A1C1E),
                disabledTextColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF1A1C1E).copy(alpha = 0.5f)
            ),
            maxLines = 3,
            textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
        )

        // Send button
        val hasText = value.isNotBlank() && !isActive
        val sendScale by animateFloatAsState(
            targetValue = if (hasText) 1f else 0.7f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
            label = "sendScale"
        )
        val sendAlpha by animateFloatAsState(
            targetValue = if (hasText) 1f else 0.3f,
            animationSpec = tween(200), label = "sendAlpha"
        )

        IconButton(
            onClick = {
                focusManager.clearFocus()
                onSend()
            },
            enabled = hasText,
            modifier = Modifier
                .scale(sendScale)
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    if (hasText) Brush.horizontalGradient(listOf(AccentPurple, AccentBlue))
                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                )
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (hasText) Color.White else (if (isDark) Color.White.copy(alpha = sendAlpha) else Color.Black.copy(alpha = sendAlpha)),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return DateFormat.format("hh:mm a", Date(timestamp)).toString()
}

private fun statusLabel(status: AutomationStatus): String = when (status) {
    AutomationStatus.IDLE -> "Idle"
    AutomationStatus.AWAITING_USER_INPUT -> "Waiting for you…"
    AutomationStatus.PARSING_GOAL -> "Parsing goal…"
    AutomationStatus.CAPTURING_SCREEN -> "Capturing screen…"
    AutomationStatus.BUILDING_UI_STATE -> "Analysing UI…"
    AutomationStatus.PREDICTING_ACTION -> "Deciding next action…"
    AutomationStatus.EXECUTING_ACTION -> "Executing action…"
    AutomationStatus.WAITING_FOR_SCREEN -> "Waiting for screen…"
    AutomationStatus.COMPLETED -> "Task Completed"
    AutomationStatus.FAILED -> "Task Failed"
    AutomationStatus.CANCELLED -> "Cancelled"
}
