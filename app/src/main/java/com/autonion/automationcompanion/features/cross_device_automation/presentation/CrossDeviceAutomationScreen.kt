package com.autonion.automationcompanion.features.cross_device_automation.presentation

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionDisclosureDialog
import com.autonion.automationcompanion.ui.components.AuroraBackground
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Accessibility
import com.autonion.automationcompanion.features.omni_chatbot.ui.LocalStartWalkthrough
import com.autonion.automationcompanion.features.cross_device_automation.engine.HardwareButtonMapper
import com.autonion.automationcompanion.ui.components.ChatHistoryPanel
import com.autonion.automationcompanion.ui.components.ConnectionRequiredOverlay
import com.autonion.automationcompanion.core.onboarding.OnboardingPreferences
import com.autonion.automationcompanion.ui.components.FeatureTipSheet
import com.autonion.automationcompanion.ui.rememberScreenWidthDp
import kotlinx.coroutines.launch
import java.util.Date

// ─── Color Palette ────────────────────────────────────────────
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)
private val UserBubbleBg = Color(0xFF7C4DFF)
private val SystemBubbleBg = Color(0xFF1E2030)
private val GlassBg = Color(0xFF1A1D2E).copy(alpha = 0.55f)
private val GlassBorder = Color.White.copy(alpha = 0.08f)
private val InputBarBg = Color(0xFF1A1D2E).copy(alpha = 0.7f)

// ═══════════════════════════════════════════════════════════════
//  MAIN SCREEN
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossDeviceAutomationScreen(onBack: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }


    val scope = rememberCoroutineScope()
    val startWalkthrough = LocalStartWalkthrough.current
    var showHardwareRemoteSheet by remember { mutableStateOf(false) }
    val isHardwareRemoteActive by HardwareButtonMapper.isActive.collectAsState()

    // ── First-visit Feature Tip ──
    val onboardingPrefs = remember { OnboardingPreferences.getInstance(context) }
    var showTip by remember { mutableStateOf(!onboardingPrefs.hasTipBeenSeen("cross_device")) }

    if (showTip) {
        FeatureTipSheet(
            title = "Cross-Device Automation",
            tips = listOf(
                "Install the **Desktop Agent** on your PC first",
                "Both devices must be on the **same WiFi network**",
                "**Clipboard sync** is automatic once connected!"
            ),
            icon = androidx.compose.material.icons.Icons.Default.Devices,
            iconColor = Color(0xFF7C4DFF),
            youtubeLink = null,
            onDismiss = { onboardingPrefs.markTipSeen("cross_device"); showTip = false },
            onShowWalkthrough = { showTip = false; startWalkthrough("cross_device") }
        )
    }

    // NOTE: Clipboard sync lifecycle observer has been moved to AppNavHost
    // so it runs globally across all screens, not just this one.

    if (showPermissionDialog) {
        PermissionDisclosureDialog(
            showDialog = showPermissionDialog,
            title = "Accessibility Service Required",
            description = "Autonion uses the Accessibility Service for clipboard sync and executing automation actions across connected devices. Please enable it in the next screen.",
            icon = Icons.Default.Accessibility,
            onDismiss = { showPermissionDialog = false },
            onContinue = {
                showPermissionDialog = false
                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        )
    }

    AuroraBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Cross-Device",
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showHardwareRemoteSheet = true }) {
                            Icon(Icons.Default.SettingsRemote, contentDescription = "Hardware Remote", tint = if (isHardwareRemoteActive) AccentPurple else Color.White)
                        }
                        IconButton(onClick = { startWalkthrough("cross_device") }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Take a Walkthrough", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        ) { innerPadding ->
            // ─── Connection State for Overlay ────────────
            // Cross-Device only needs a connected Desktop Agent.
            // The Agent handles its own LLM (Ollama or Cloud API).
            val crossManager = remember { CrossDeviceAutomationManager.getInstance(context) }
            val devices by crossManager.deviceRepository.getAllDevices().collectAsState(initial = emptyList())
            val hasAgentConnection = devices.any {
                it.isSelected && it.status == com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus.ONLINE
            }
            val isAIReady = hasAgentConnection

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
            ) {
                // ─── Hardware Remote Active Banner ─────────────
                AnimatedVisibility(visible = isHardwareRemoteActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .background(AccentPurple.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Desktop Remote Active",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        TextButton(
                            onClick = { HardwareButtonMapper.deactivate() },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Stop")
                        }
                    }
                }

                // ─── Tab Row ────────────────────
                StyledTabRow(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )

                // ─── Tab Content ────────────────
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(tween(250)) + slideInHorizontally(
                            tween(250),
                            initialOffsetX = { if (targetState > initialState) it / 4 else -it / 4 }
                        ) togetherWith fadeOut(tween(200))
                    },
                    label = "TabContent"
                ) { tab ->
                    when (tab) {
                        0 -> DeviceManagementScreen(
                            onAccessibilityNeeded = { showPermissionDialog = true }
                        )
                        1, 2 -> {
                            // Ask & Rules tabs need agent + LLM connection
                            Box(modifier = Modifier.fillMaxSize()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(if (!isAIReady) Modifier.blur(12.dp) else Modifier)
                                    ) {
                                    when (tab) {
                                        1 -> DesktopAutomationScreen()
                                        2 -> PromptScreen()
                                    }
                                }
                                if (!isAIReady) {
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
                                        ConnectionRequiredOverlay(
                                            message = "Connect to a Desktop Agent to use Cross-Device Automation.",
                                            steps = listOf(
                                                "Go to the Devices tab and select a desktop.",
                                                "The AI model is configured on the Desktop Agent.",
                                                "Open Agent Settings → AI Settings to select a model."
                                            ),
                                            optionalChip = "Desktop Agent not connected"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (showHardwareRemoteSheet) {
            HardwareRemoteSheet(onDismissRequest = { showHardwareRemoteSheet = false })
        }
    }
}

// ─── Styled Tab Row ──────────────────────────────────────────

private data class TabItem(val title: String, val icon: ImageVector)

@Composable
private fun StyledTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf(
        TabItem("Devices", Icons.Default.Devices),
        TabItem("Rules", Icons.AutoMirrored.Filled.Rule),
        TabItem("Ask", Icons.Default.SmartToy)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassBg),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == index
            val animatedAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0f,
                animationSpec = tween(200), label = "tabAlpha"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) Brush.horizontalGradient(
                            listOf(AccentPurple.copy(alpha = 0.4f), AccentBlue.copy(alpha = 0.3f))
                        ) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    )
                    .then(
                        if (!isSelected) Modifier.background(Color.Transparent)
                            .clip(RoundedCornerShape(12.dp))
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tab.title,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  ASK TAB — Chat UI
// ═══════════════════════════════════════════════════════════════

@Composable
fun PromptScreen() {
    val context = LocalContext.current
    val manager = CrossDeviceAutomationManager.getInstance(context)
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel { PromptViewModel(manager, context.applicationContext) }

    val inputQuery by viewModel.inputQuery.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val isAutomationActive by viewModel.isAutomationActive.collectAsState()
    val showHistory by viewModel.showHistory.collectAsState()
    val chatHistorySessions by viewModel.chatHistorySessions.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.firstOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ─── Chat Header (New Chat + History) ────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.clearChat() }) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "New Chat",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("New Chat", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.toggleHistory() }) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = "History",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("History", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
            }

            // ─── Messages ──────────────────────
            if (messages.isEmpty()) {
                // Empty State
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
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(message)
                    }
                }
            }

            // ─── Input Bar ──────────────────────
            if (isAutomationActive) {
                Button(
                    onClick = { viewModel.stopAutomation() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Stop Automation", color = Color.White)
                }
            } else {
                ChatInputBar(
                    value = inputQuery,
                    onValueChange = viewModel::onQueryChanged,
                    onSend = viewModel::sendPrompt
                )
            }
        }

        // ─── History Panel Overlay ──────────────────
        AnimatedVisibility(
            visible = showHistory,
            enter = fadeIn(tween(200)) + slideInHorizontally(tween(300)) { -it },
            exit = fadeOut(tween(200)) + slideOutHorizontally(tween(250)) { -it }
        ) {
            ChatHistoryPanel(
                sessions = chatHistorySessions,
                onSessionClick = { session ->
                    viewModel.loadChatSession(session.sessionId)
                },
                onDeleteSession = { viewModel.deleteSession(it.sessionId) },
                onClose = { viewModel.toggleHistory() }
            )
        }
    }
}

@Composable
private fun EmptyChatState() {
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
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Send commands to your connected devices",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.isUser
    val screenWidth = rememberScreenWidthDp()
    val bubbleMaxWidth = (screenWidth * 0.65f).coerceAtMost(480.dp)

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
                            listOf(SystemBubbleBg, SystemBubbleBg)
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.text,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }

            // Timestamp
            Text(
                text = formatTime(message.timestamp),
                color = Color.White.copy(alpha = 0.3f),
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
    onSend: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp)
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
                    "Ask something...",
                    color = Color.White.copy(alpha = 0.35f)
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
            textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
        )

        // Send button
        val hasText = value.isNotBlank()
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
                tint = Color.White.copy(alpha = sendAlpha),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return DateFormat.format("hh:mm a", Date(timestamp)).toString()
}
