package com.autonion.automationcompanion.features.cross_device_automation.presentation

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.flow_automation.data.DesktopFlowManifest
import com.autonion.automationcompanion.features.flow_automation.data.FlowTriggerProgress
import com.autonion.automationcompanion.features.flow_automation.data.FlowTriggerStatus
import com.autonion.automationcompanion.features.system_context_automation.shared.ui.PermissionDisclosureDialog
import com.autonion.automationcompanion.ui.components.AuroraBackground
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Close
import com.autonion.automationcompanion.features.omni_chatbot.ui.LocalStartWalkthrough
import com.autonion.automationcompanion.features.cross_device_automation.engine.HardwareButtonMapper
import com.autonion.automationcompanion.ui.components.YouTubeTutorials
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.platform.LocalUriHandler
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

    val isDark = isSystemInDarkTheme()
    val headerTextColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val headerSubTextColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1A1C1E).copy(alpha = 0.65f)

    val scope = rememberCoroutineScope()
    val startWalkthrough = LocalStartWalkthrough.current
    val uriHandler = LocalUriHandler.current
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
            youtubeLink = YouTubeTutorials.CROSS_DEVICE,
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
                            fontSize = 22.sp,
                            color = headerTextColor
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = headerTextColor)
                        }
                    },
                    actions = {
                        IconButton(onClick = { uriHandler.openUri(YouTubeTutorials.CROSS_DEVICE) }) {
                            Icon(Icons.Default.PlayCircle, contentDescription = "Watch Video Tutorial", tint = headerTextColor)
                        }
                        IconButton(onClick = { showHardwareRemoteSheet = true }) {
                            Icon(Icons.Default.SettingsRemote, contentDescription = "Hardware Remote", tint = if (isHardwareRemoteActive) AccentPurple else headerTextColor)
                        }
                        IconButton(onClick = { startWalkthrough("cross_device") }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Take a Walkthrough", tint = headerTextColor)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = headerTextColor,
                        navigationIconContentColor = headerTextColor
                    )
                )
            }
        ) { innerPadding ->
            // ─── Connection State for Overlay ────────────
            // Distinguish full desktop agent from background service (unlock helper)
            val crossManager = remember { CrossDeviceAutomationManager.getInstance(context) }
            val devices by crossManager.deviceRepository.getAllDevices().collectAsState(initial = emptyList())
            val hasFullAgentConnection = devices.any {
                it.isSelected && it.status == com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus.ONLINE && !it.isServiceOnly
            }
            val hasAnyConnection = devices.any {
                it.isSelected && it.status == com.autonion.automationcompanion.features.cross_device_automation.domain.DeviceStatus.ONLINE
            }
            val isServiceOnlyConnected = hasAnyConnection && !hasFullAgentConnection

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
                            color = headerTextColor,
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
                    isDark = isDark,
                    headerTextColor = headerTextColor,
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
                        1 -> {
                            // Rules tab: needs full agent connection
                            val shouldBlur = !hasFullAgentConnection
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (shouldBlur) Modifier.blur(12.dp) else Modifier)
                                ) {
                                    DesktopAutomationScreen()
                                }
                                if (shouldBlur) {
                                    BlurOverlay(
                                        isDark = isDark,
                                        isServiceOnlyConnected = isServiceOnlyConnected
                                    )
                                }
                            }
                        }
                        2 -> {
                            // Flows tab: accessible with any connection (including service-only for unlock flows)
                            val shouldBlur = !hasAnyConnection
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (shouldBlur) Modifier.blur(12.dp) else Modifier)
                                ) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        // Service-only banner when connected to background service
                                        if (isServiceOnlyConnected) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                                                    .background(
                                                        Color(0xFFFFB74D).copy(alpha = 0.15f),
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Lock,
                                                    contentDescription = null,
                                                    tint = Color(0xFFFFB74D),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text(
                                                    "Pre-login Mode • Unlock Flows Available",
                                                    color = if (isDark) Color.White.copy(alpha = 0.8f) else Color(0xFF1A1C1E).copy(alpha = 0.75f),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                        DesktopFlowsTab()
                                    }
                                }
                                if (shouldBlur) {
                                    BlurOverlay(
                                        isDark = isDark,
                                        isServiceOnlyConnected = false // No service; fully disconnected
                                    )
                                }
                            }
                        }
                        3 -> {
                            // Ask tab: needs full agent connection
                            val shouldBlur = !hasFullAgentConnection
                            Box(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (shouldBlur) Modifier.blur(12.dp) else Modifier)
                                ) {
                                    PromptScreen()
                                }
                                if (shouldBlur) {
                                    BlurOverlay(
                                        isDark = isDark,
                                        isServiceOnlyConnected = isServiceOnlyConnected
                                    )
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
private fun StyledTabRow(selectedTab: Int, isDark: Boolean, headerTextColor: Color, onTabSelected: (Int) -> Unit) {
    val tabs = listOf(
        TabItem("Devices", Icons.Default.Devices),
        TabItem("Rules", Icons.AutoMirrored.Filled.Rule),
        TabItem("Flows", Icons.Default.AccountTree),
        TabItem("Ask", Icons.Default.SmartToy)
    )

    val glassBgColor = if (isDark) GlassBg else Color.White.copy(alpha = 0.7f)
    val unselectedColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (!isDark) Modifier.border(1.dp, Color.Black.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                else Modifier
            )
            .background(glassBgColor),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = selectedTab == index

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) Brush.horizontalGradient(
                            listOf(AccentPurple.copy(alpha = if (isDark) 0.4f else 0.85f), AccentBlue.copy(alpha = if (isDark) 0.3f else 0.75f))
                        ) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    )
                    .clickable { onTabSelected(index) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    tab.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isSelected) Color.White else unselectedColor
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    tab.title,
                    color = if (isSelected) Color.White else unselectedColor,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 11.sp,
                    maxLines = 1
                )
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
    val stopRequested by viewModel.stopRequested.collectAsState()
    val showHistory by viewModel.showHistory.collectAsState()
    val chatHistorySessions by viewModel.chatHistorySessions.collectAsState()
    val showSaveAsFlow by viewModel.showSaveAsFlow.collectAsState()
    val lastUserPrompt by viewModel.lastUserPrompt.collectAsState()
    val listState = rememberLazyListState()

    val isDark = isSystemInDarkTheme()
    val headerTextColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val headerSubTextColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1A1C1E).copy(alpha = 0.65f)

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
                        tint = headerTextColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("New Chat", color = headerTextColor.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.toggleHistory() }) {
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

            // ─── Messages ──────────────────────
            if (messages.isEmpty()) {
                // Empty State
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
                        ChatBubble(message, isDark)
                    }
                }
            }

            // ─── Save as Flow Card ──────────────────────
            AnimatedVisibility(
                visible = showSaveAsFlow,
                enter = fadeIn(tween(300)) + expandVertically(tween(300)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
            ) {
                SaveAsFlowCard(
                    defaultName = lastUserPrompt?.take(40) ?: "AI-Generated Flow",
                    onSave = { flowName -> viewModel.saveAsFlow(flowName) },
                    onDismiss = { viewModel.dismissSaveAsFlow() },
                    isDark = isDark
                )
            }

            // ─── Input Bar ──────────────────────
            if (isAutomationActive) {
                Button(
                    onClick = { viewModel.stopAutomation() },
                    enabled = !stopRequested,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (stopRequested)
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        else
                            MaterialTheme.colorScheme.error,
                        disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (stopRequested) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White.copy(alpha = 0.7f),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Stopping...", color = Color.White.copy(alpha = 0.7f))
                    } else {
                        Text("Stop Automation", color = Color.White)
                    }
                }
            } else {
                ChatInputBar(
                    value = inputQuery,
                    onValueChange = viewModel::onQueryChanged,
                    onSend = viewModel::sendPrompt,
                    isDark = isDark
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
            "Send commands to your connected devices",
            color = headerSubTextColor,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, isDark: Boolean) {
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
                Text(
                    text = message.text,
                    color = if (isUser) Color.White else systemBubbleText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
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
            placeholder = {
                Text(
                    "Ask something...",
                    color = if (isDark) Color.White.copy(alpha = 0.35f) else Color(0xFF1A1C1E).copy(alpha = 0.45f)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = AccentPurple,
                focusedTextColor = if (isDark) Color.White else Color(0xFF1A1C1E),
                unfocusedTextColor = if (isDark) Color.White else Color(0xFF1A1C1E)
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
                tint = if (hasText) Color.White else (if (isDark) Color.White.copy(alpha = sendAlpha) else Color.Black.copy(alpha = sendAlpha)),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    return DateFormat.format("hh:mm a", Date(timestamp)).toString()
}

// ═══════════════════════════════════════════════════════════════
//  DESKTOP FLOWS TAB
// ═══════════════════════════════════════════════════════════════

@Composable
fun DesktopFlowsTab() {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val headerTextColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val headerSubTextColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1A1C1E).copy(alpha = 0.65f)

    val crossManager = remember { CrossDeviceAutomationManager.getInstance(context) }
    val flowManager = crossManager.desktopFlowManager

    val desktopFlows by flowManager.desktopFlows.collectAsState()
    val isLoading by flowManager.isLoading.collectAsState()
    val runningFlowId by flowManager.runningFlowId.collectAsState()

    // Track progress
    var lastProgress by remember { mutableStateOf<FlowTriggerProgress?>(null) }
    LaunchedEffect(Unit) {
        flowManager.requestFlowList()
        flowManager.progressUpdates.collect { progress ->
            lastProgress = progress
            if (progress.status == FlowTriggerStatus.COMPLETED ||
                progress.status == FlowTriggerStatus.FAILED ||
                progress.status == FlowTriggerStatus.STOPPED) {
                kotlinx.coroutines.delay(5000)
                if (lastProgress?.flowId == progress.flowId) {
                    lastProgress = null
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header row with count + refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                tint = AccentPurple,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Desktop Flows",
                color = headerTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            if (desktopFlows.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "(${desktopFlows.size})",
                    color = headerSubTextColor,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = AccentPurple
                )
            } else {
                IconButton(
                    onClick = { flowManager.requestFlowList() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = headerTextColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (desktopFlows.isEmpty() && !isLoading) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                DesktopFlowsEmptyState(isDark, headerTextColor, headerSubTextColor)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(desktopFlows, key = { it.id }) { manifest ->
                    DesktopFlowCard(
                        manifest = manifest,
                        isDark = isDark,
                        isRunning = runningFlowId == manifest.id,
                        anyFlowRunning = runningFlowId != null,
                        progress = lastProgress?.takeIf { it.flowId == manifest.id },
                        onTrigger = { flowManager.triggerFlow(manifest.id) },
                        onStop = { flowManager.stopFlow(manifest.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopFlowsEmptyState(isDark: Boolean, headerTextColor: Color, headerSubTextColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "flowPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.AccountTree,
            contentDescription = null,
            modifier = Modifier.size(64.dp).scale(pulseScale),
            tint = AccentPurple.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No desktop flows found",
            color = headerTextColor.copy(alpha = 0.6f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Create flows in the Desktop Agent's\nFlow Builder, then refresh here.",
            color = headerSubTextColor,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun DesktopFlowCard(
    manifest: DesktopFlowManifest,
    isDark: Boolean,
    isRunning: Boolean,
    anyFlowRunning: Boolean,
    progress: FlowTriggerProgress?,
    onTrigger: () -> Unit,
    onStop: () -> Unit
) {
    val glassBg = if (isDark) GlassBg else Color.White.copy(alpha = 0.75f)
    val glassBorder = if (isDark) GlassBorder else Color.Black.copy(alpha = 0.06f)
    val textColor = if (isDark) Color.White else Color(0xFF1A1C1E)
    val secondaryColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color(0xFF1A1C1E).copy(alpha = 0.6f)

    val borderColor = when {
        isRunning -> AccentPurple
        else -> glassBorder
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(listOf(glassBg, glassBg.copy(alpha = 0.45f)))
            )
            .border(if (isRunning) 2.dp else 1.dp, borderColor, RoundedCornerShape(20.dp))
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(AccentPurple.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = AccentPurple,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Flow info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        manifest.name,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1
                    )
                    if (manifest.description.isNotEmpty()) {
                        Text(
                            manifest.description,
                            color = secondaryColor,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        // Node count chip
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "${manifest.nodeCount} nodes",
                                fontSize = 10.sp,
                                color = secondaryColor.copy(alpha = 0.8f)
                            )
                        }
                        // Trigger type chip
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                manifest.triggerType,
                                fontSize = 10.sp,
                                color = secondaryColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                // Play / Stop button
                if (isRunning) {
                    FilledIconButton(
                        onClick = onStop,
                        modifier = Modifier.size(42.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFEF5350).copy(alpha = 0.15f),
                            contentColor = Color(0xFFEF5350)
                        )
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Stop Flow",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = onTrigger,
                        enabled = !anyFlowRunning,
                        modifier = Modifier.size(42.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = AccentPurple.copy(alpha = if (anyFlowRunning) 0.06f else 0.15f),
                            contentColor = AccentPurple.copy(alpha = if (anyFlowRunning) 0.3f else 1f)
                        )
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Run on Desktop",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Progress indicator
            if (progress != null) {
                Spacer(modifier = Modifier.height(10.dp))
                val progressColor = when (progress.status) {
                    FlowTriggerStatus.COMPLETED -> Color(0xFF4CAF50)
                    FlowTriggerStatus.FAILED -> Color(0xFFEF5350)
                    FlowTriggerStatus.STOPPED -> Color(0xFFFFA726)
                    else -> AccentPurple
                }
                if (progress.totalSteps > 0) {
                    LinearProgressIndicator(
                        progress = { progress.currentStep.toFloat() / progress.totalSteps },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = progressColor,
                        trackColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
                    )
                }
                Text(
                    progress.message,
                    fontSize = 12.sp,
                    color = progressColor,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1
                )
            }
        }
    }
}

// ─── Save as Flow Card ───────────────────────────────────────
@Composable
private fun SaveAsFlowCard(
    defaultName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean
) {
    var flowName by remember(defaultName) { mutableStateOf(defaultName) }
    val cardBg = if (isDark) Color(0xFF1E2430) else Color(0xFFF0F2FF)
    val borderColor = AccentPurple.copy(alpha = 0.4f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Title row with dismiss
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = AccentPurple,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Save as Flow",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = if (isDark) Color.White else Color(0xFF1A1C1E)
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = (if (isDark) Color.White else Color.Black).copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Convert these AI actions into a reusable automation flow",
                fontSize = 12.sp,
                color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.55f)
            )

            Spacer(Modifier.height(10.dp))

            // Flow name input
            OutlinedTextField(
                value = flowName,
                onValueChange = { flowName = it },
                label = { Text("Flow Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentPurple,
                    unfocusedBorderColor = borderColor,
                    focusedLabelColor = AccentPurple,
                    cursorColor = AccentPurple
                )
            )

            Spacer(Modifier.height(10.dp))

            // Save button
            Button(
                onClick = { if (flowName.isNotBlank()) onSave(flowName.trim()) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                enabled = flowName.isNotBlank()
            ) {
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Save as Flow", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * Blur overlay shown on tabs that are disabled.
 * Shows different messaging for fully disconnected vs service-only connected.
 */
@Composable
private fun BlurOverlay(
    isDark: Boolean,
    isServiceOnlyConnected: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xCC0F1115) else Color(0xCCF3F6FD))
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
        if (isServiceOnlyConnected) {
            ConnectionRequiredOverlay(
                title = "Agent App is Closed",
                message = "The background service is connected for Screen Unlock.\nUse the Flows tab to unlock your PC.\n\nTo run AI prompts or Rules, open Autonion Agent on your PC.",
                steps = emptyList(),
                optionalChip = "Service Connected (Agent App Closed)"
            )
        } else {
            ConnectionRequiredOverlay(
                message = "Connect to a Desktop Agent to use Cross-Device Automation.",
                steps = listOf(
                    "Go to the Devices tab and select a desktop.",
                    "The AI model is configured on the Desktop Agent.",
                    "Open Agent Settings \u2192 AI Settings to select a model."
                ),
                optionalChip = "Desktop Agent not connected"
            )
        }
    }
}
