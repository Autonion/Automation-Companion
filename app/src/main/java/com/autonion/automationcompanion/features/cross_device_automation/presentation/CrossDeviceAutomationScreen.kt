package com.autonion.automationcompanion.features.cross_device_automation.presentation

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.ui.components.AuroraBackground
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

    LaunchedEffect(Unit) {
        if (!com.autonion.automationcompanion.AccessibilityRouter.isServiceConnected()) {
            showPermissionDialog = true
        }
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    kotlinx.coroutines.delay(500)
                    CrossDeviceAutomationManager.getInstance(context).syncClipboard(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("Permission Required") },
            text = { Text("This feature requires the Automation Companion Accessibility Service to function (for Clipboard Sync). Please enable it in Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionDialog = false
                    val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) { Text("Cancel") }
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
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
                        0 -> PromptScreen()
                        1 -> DesktopAutomationScreen()
                        2 -> DeviceManagementScreen()
                    }
                }
            }
        }
    }
}

// ─── Styled Tab Row ──────────────────────────────────────────

private data class TabItem(val title: String, val icon: ImageVector)

@Composable
private fun StyledTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf(
        TabItem("Ask", Icons.Default.SmartToy),
        TabItem("Rules", Icons.AutoMirrored.Filled.Rule),
        TabItem("Devices", Icons.Default.Devices)
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
    val viewModel = androidx.lifecycle.viewmodel.compose.viewModel { PromptViewModel(manager) }

    val inputQuery by viewModel.inputQuery.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
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
        ChatInputBar(
            value = inputQuery,
            onValueChange = viewModel::onQueryChanged,
            onSend = viewModel::sendPrompt
        )
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
            onClick = onSend,
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
