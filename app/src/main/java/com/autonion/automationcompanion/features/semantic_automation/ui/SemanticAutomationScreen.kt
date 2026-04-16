package com.autonion.automationcompanion.features.semantic_automation.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.semantic_automation.core.SemanticAutomationService
import com.autonion.automationcompanion.features.semantic_automation.model.AutomationStatus
import com.autonion.automationcompanion.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticAutomationScreen(
    onBack: () -> Unit,
    onOpenModelManager: () -> Unit = {},
    onStart: (String) -> Unit,
    onStop: () -> Unit
) {
    var command by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    // Observe service state
    val engine by SemanticAutomationService.activeEngine.collectAsState()

    val status by engine?.status?.collectAsState() ?: remember { mutableStateOf(AutomationStatus.IDLE) }
    val goal by engine?.currentGoal?.collectAsState() ?: remember { mutableStateOf(null) }
    val loopCount by engine?.loopCount?.collectAsState() ?: remember { mutableStateOf(0) }
    val lastAction by engine?.lastActionDescription?.collectAsState() ?: remember { mutableStateOf(null) }

    val userPromptMessage by engine?.userPromptMessage?.collectAsState() ?: remember { mutableStateOf(null) }
    val userPromptOptions by engine?.userPromptOptions?.collectAsState() ?: remember { mutableStateOf(emptyList()) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Semantic Automation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { 
                        com.autonion.automationcompanion.features.semantic_automation.ml.PredictorCache.disconnect() 
                    }) {
                        Icon(Icons.Default.PowerOff, contentDescription = "Disconnect ML")
                    }
                    IconButton(onClick = onOpenModelManager) {
                        Icon(Icons.Default.Settings, contentDescription = "SLM Hub")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Header Card ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(AccentPurple, AccentBlue)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                "AI Agent",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Describe what you want to automate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // ── Command Input ──
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("What should the agent do?") },
                placeholder = { Text("e.g. search shoes on amazon") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    if (command.isNotBlank()) onStart(command)
                }),
                enabled = !isActive,
                singleLine = false,
                maxLines = 3
            )

            // ── Example chips ──
            AnimatedVisibility(visible = !isActive) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Try these:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("open settings", "search shoes on amazon").forEach { example ->
                            SuggestionChip(
                                onClick = { command = example },
                                label = { Text(example, fontSize = 12.sp) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("turn on wifi", "play music on spotify").forEach { example ->
                            SuggestionChip(
                                onClick = { command = example },
                                label = { Text(example, fontSize = 12.sp) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                }
            }

            // ── Action Buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isActive) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop Agent")
                    }
                } else {
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            if (command.isNotBlank()) onStart(command)
                        },
                        enabled = command.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Start Agent")
                    }
                }
            }

            // ── Interactive User Prompt ──
            AnimatedVisibility(visible = status == AutomationStatus.AWAITING_USER_INPUT && userPromptMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.HelpOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                userPromptMessage ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        // Options buttons
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            userPromptOptions.forEach { option ->
                                Button(
                                    onClick = { engine?.resumeWithUserChoice(option) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text(option)
                                }
                            }
                        }
                    }
                }
            }

            // ── Live Status Card ──
            AnimatedVisibility(
                visible = isActive || status == AutomationStatus.COMPLETED || status == AutomationStatus.FAILED
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (status) {
                            AutomationStatus.COMPLETED -> AccentGreenContainer
                            AutomationStatus.FAILED -> AccentRedContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
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
                                    modifier = Modifier.size(20.dp).alpha(pulseAlpha),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
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
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                statusLabel(status),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        goal?.let { g ->
                            Text(
                                "Goal: ${g.rawCommand}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Task: ${g.task} | App: ${g.targetApp ?: "—"} | Query: ${g.query ?: "—"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (loopCount > 0) {
                            Text(
                                "Loop iteration: $loopCount",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        lastAction?.let { desc ->
                            Text(
                                "Last: $desc",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Footer hint ──
            Text(
                "The agent captures the screen, understands UI elements, and performs actions automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
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
