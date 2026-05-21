package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.autonion.automationcompanion.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationRule
import com.autonion.automationcompanion.features.cross_device_automation.domain.RuleAction
import android.content.Intent
import com.autonion.automationcompanion.ui.theme.AppTheme

// ─── Colors ───────────────────────────────────────────────────
private val CardGlass = Color(0xFF1A1D2E).copy(alpha = 0.55f)
private val CardBorder = Color.White.copy(alpha = 0.08f)
private val AccentPurple = Color(0xFF7C4DFF)
private val AccentBlue = Color(0xFF448AFF)
private val MeetingColor = Color(0xFFFF6B6B)
private val SocialColor = Color(0xFF48C9B0)
private val WorkColor = Color(0xFF5DADE2)
private val CustomColor = Color(0xFFAF7AC5)

@Composable
fun DesktopAutomationScreen() {
    val context = LocalContext.current
    val manager = CrossDeviceAutomationManager.getInstance(context)
    val viewModel = viewModel { DesktopAutomationViewModel(manager) }
    val rules by viewModel.rules.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }

    // FAB bounce animation
    var fabVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { 
        kotlinx.coroutines.delay(300)
        fabVisible = true 
    }
    val fabScale by animateFloatAsState(
        targetValue = if (fabVisible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "fabScale"
    )

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.scale(fabScale),
                containerColor = AccentPurple,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Rule", fontWeight = FontWeight.SemiBold)
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (rules.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyRulesState()
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    itemsIndexed(rules) { index, rule ->
                        StaggeredRuleItem(
                            rule = rule,
                            index = index,
                            onDelete = { viewModel.deleteRule(rule.id) }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateDesktopRuleDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, category, url, actions ->
                viewModel.createRule(name, category, url, actions)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun EmptyRulesState() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emptyPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Glassmorphic Info Banner Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(CardGlass.copy(alpha = 0.7f), CardGlass.copy(alpha = 0.35f))
                    )
                )
                .background(CardBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Glowy computer-to-phone icon
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AccentPurple.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .scale(pulseScale),
                        tint = AccentPurple
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Desktop-to-Mobile Rules",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Automate your phone based on what you browse on your PC! When a connected computer visits meetings, social, or work sites, your phone triggers designated settings instantly.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Simple visual how-to guide steps
        Text(
            text = "HOW IT WORKS",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HowItWorksStep(
                number = "1",
                title = "Browse PC",
                subtitle = "Open a website category or URL on your connected PC.",
                modifier = Modifier.weight(1f)
            )
            HowItWorksStep(
                number = "2",
                title = "Trigger Sync",
                subtitle = "PC Agent sends sync event to your phone.",
                modifier = Modifier.weight(1f)
            )
            HowItWorksStep(
                number = "3",
                title = "Automate",
                subtitle = "Your phone mutes, opens apps, changes display, etc.",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HowItWorksStep(number: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(CardGlass.copy(alpha = 0.35f))
            .background(CardBorder, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(AccentPurple.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = number,
                    color = AccentPurple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun StaggeredRuleItem(rule: AutomationRule, index: Int, onDelete: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 80L)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 }
    ) {
        RuleGlassCard(rule = rule, onDelete = onDelete)
    }
}

@Composable
private fun RuleGlassCard(rule: AutomationRule, onDelete: () -> Unit) {
    val categoryInfo = getCategoryInfo(rule)
    val friendlyTriggerText = when {
        categoryInfo.description.contains("meeting", ignoreCase = true) -> 
            "When browsing Meeting sites on PC (Zoom, Teams, Meet...)"
        categoryInfo.description.contains("social", ignoreCase = true) -> 
            "When browsing Social Media on PC (YouTube, Twitter, Reddit...)"
        categoryInfo.description.contains("work", ignoreCase = true) -> 
            "When browsing Work Portals on PC (GitHub, Slack, Jira...)"
        categoryInfo.description.contains("URL:", ignoreCase = true) -> 
            "When visiting website containing: ${categoryInfo.description.removePrefix("URL: ")}"
        else -> "When trigger matches: ${categoryInfo.description}"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(CardGlass, CardGlass.copy(alpha = 0.45f))
                )
            )
            .background(CardBorder, RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category icon
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(categoryInfo.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    categoryInfo.icon,
                    contentDescription = null,
                    tint = categoryInfo.color,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    friendlyTriggerText,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(Modifier.height(10.dp))
                
                // Horizontal Flow of action badges
                if (rule.actions.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        rule.actions.forEach { action ->
                            ActionBadge(action)
                        }
                    }
                } else {
                    Text(
                        "No actions configured",
                        color = Color.White.copy(alpha = 0.35f),
                        fontSize = 11.sp
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .align(Alignment.Top)
                    .padding(top = 2.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFFF6B6B).copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ActionBadge(action: RuleAction) {
    val info = when (action.type) {
        "set_volume" -> Pair(Icons.AutoMirrored.Rounded.VolumeUp, "Set Volume")
        "enable_dnd" -> Pair(Icons.Rounded.DoNotDisturb, "Enable DND")
        "send_notification" -> Pair(Icons.Rounded.Notifications, "Notify")
        "set_brightness" -> Pair(Icons.Rounded.Brightness6, "Brightness")
        "set_auto_rotate" -> Pair(Icons.Rounded.ScreenRotation, "Auto-Rotate")
        "set_screen_timeout" -> {
            val durationMs = action.parameters["duration_ms"]?.toIntOrNull() ?: 0
            if (durationMs == Int.MAX_VALUE) {
                Pair(Icons.Rounded.Visibility, "Keep Awake")
            } else {
                Pair(Icons.Rounded.Timer, "Screen Timeout")
            }
        }
        "send_sms" -> Pair(Icons.AutoMirrored.Rounded.Message, "Send SMS")
        "launch_app" -> Pair(Icons.Rounded.Apps, "Launch App")
        "set_battery_saver" -> Pair(Icons.Rounded.BatterySaver, "Battery Saver")
        else -> Pair(Icons.Rounded.AutoFixHigh, action.type)
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = info.first,
            contentDescription = null,
            tint = AccentPurple,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = info.second,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}


private data class CategoryInfo(val icon: ImageVector, val color: Color, val description: String)

private fun getCategoryInfo(rule: AutomationRule): CategoryInfo {
    val condition = rule.conditions.firstOrNull()
    val conditionText = if (condition is com.autonion.automationcompanion.features.cross_device_automation.domain.RuleCondition.PayloadContains) {
        if (condition.key == "url") "URL: ${condition.value}" else condition.value
    } else "Unknown"

    return when {
        conditionText.contains("meeting", ignoreCase = true) -> CategoryInfo(Icons.Default.MeetingRoom, MeetingColor, conditionText)
        conditionText.contains("social", ignoreCase = true) -> CategoryInfo(Icons.Default.People, SocialColor, conditionText)
        conditionText.contains("work", ignoreCase = true) -> CategoryInfo(Icons.Default.Work, WorkColor, conditionText)
        else -> CategoryInfo(Icons.Default.Language, CustomColor, conditionText)
    }
}

// ═══════════════════════════════════════════════════════════════
//  CREATE RULE DIALOG (Premium overhaul)
// ═══════════════════════════════════════════════════════════════

@Composable
fun CreateDesktopRuleDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, category: String, url: String?, actions: List<ConfiguredAction>) -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(1) }
    var name by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Meeting") }
    var customUrl by remember { mutableStateOf("") }
    var actions by remember { mutableStateOf<List<ConfiguredAction>>(emptyList()) }

    var pendingAppActionIndex by remember { mutableStateOf(-1) }

    val appPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val packageName = result.data?.getStringExtra("selected_package_name")
            if (packageName != null && pendingAppActionIndex >= 0 && pendingAppActionIndex < actions.size) {
                 val currentAction = actions[pendingAppActionIndex]
                 if (currentAction is ConfiguredAction.AppAction) {
                      val updatedAction = currentAction.copy(packageName = packageName)
                      val newActions = actions.toMutableList()
                      newActions[pendingAppActionIndex] = updatedAction
                      actions = newActions
                 }
            }
        }
        pendingAppActionIndex = -1
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .height(600.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1A1D2E)) // Solid opaque background
                .background(CardBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            AppTheme(darkTheme = true) { // Force dark theme for this dialog to match the custom colors
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "New Sync Rule",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (step == 1) "Define trigger condition" else "Choose actions to trigger",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp
                        )
                    }
                    
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Progress indicator
                StepIndicator(step = step)
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Form content area
                Box(modifier = Modifier.weight(1f)) {
                    if (step == 1) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Rule Name") },
                                placeholder = { Text("e.g. DND Meeting Mode") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentPurple,
                                    unfocusedBorderColor = CardBorder,
                                    focusedLabelColor = AccentPurple,
                                    unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    cursorColor = AccentPurple
                                )
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                "Trigger Condition",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // 2x2 Grid of Category Card selectors
                            Row(modifier = Modifier.fillMaxWidth()) {
                                CategoryCard(
                                    category = "Meeting",
                                    label = "Meetings",
                                    description = "Zoom, Teams, Meet...",
                                    icon = Icons.Default.MeetingRoom,
                                    color = MeetingColor,
                                    isSelected = selectedCategory == "Meeting",
                                    onClick = { selectedCategory = "Meeting" },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                CategoryCard(
                                    category = "Social",
                                    label = "Social Media",
                                    description = "YouTube, Reddit, Twitter...",
                                    icon = Icons.Default.People,
                                    color = SocialColor,
                                    isSelected = selectedCategory == "Social",
                                    onClick = { selectedCategory = "Social" },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                CategoryCard(
                                    category = "Work",
                                    label = "Work Portals",
                                    description = "Slack, GitHub, Jira...",
                                    icon = Icons.Default.Work,
                                    color = WorkColor,
                                    isSelected = selectedCategory == "Work",
                                    onClick = { selectedCategory = "Work" },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                CategoryCard(
                                    category = "Custom URL",
                                    label = "Custom URL",
                                    description = "Target specific domains",
                                    icon = Icons.Default.Language,
                                    color = CustomColor,
                                    isSelected = selectedCategory == "Custom URL",
                                    onClick = { selectedCategory = "Custom URL" },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            
                            if (selectedCategory == "Custom URL") {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = customUrl,
                                    onValueChange = { customUrl = it },
                                    label = { Text("URL Contains") },
                                    placeholder = { Text("e.g. docs.google.com") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = AccentPurple,
                                        unfocusedBorderColor = CardBorder,
                                        focusedLabelColor = AccentPurple,
                                        unfocusedLabelColor = Color.White.copy(alpha = 0.4f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        cursorColor = AccentPurple
                                    )
                                )
                            }
                        }
                    } else {
                        // Step 2 content
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                ActionPicker(
                                    configuredActions = actions,
                                    onActionsChanged = { actions = it },
                                    onPickContactClicked = { /* TODO: Contact Picker */ },
                                    onPickAppClicked = { index ->
                                        pendingAppActionIndex = index
                                        val intent = Intent(context, AppPickerActivity::class.java)
                                        appPickerLauncher.launch(intent)
                                    },
                                    context = context
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Bottom Buttons Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (step == 1) {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.6f))
                        ) {
                            Text("Cancel")
                        }
                        
                        Button(
                            onClick = { step = 2 },
                            enabled = name.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPurple,
                                contentColor = Color.White,
                                disabledContainerColor = AccentPurple.copy(alpha = 0.3f),
                                disabledContentColor = Color.White.copy(alpha = 0.3f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text("Next", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        TextButton(
                            onClick = { step = 1 },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.6f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Back")
                            }
                        }
                        
                        Button(
                            onClick = {
                                val categoryStr = if (selectedCategory == "Custom URL") "Custom URL" else selectedCategory
                                val urlValue = if (selectedCategory == "Custom URL") customUrl else null
                                onCreate(name, categoryStr, urlValue, actions)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPurple,
                                contentColor = Color.White
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Create Rule", fontWeight = FontWeight.Bold)
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

@Composable
private fun StepIndicator(step: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val activeColor = AccentPurple
        val inactiveColor = Color.White.copy(alpha = 0.1f)
        
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (step >= 1) activeColor else inactiveColor)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (step >= 2) activeColor else inactiveColor)
        )
    }
}

@Composable
private fun CategoryCard(
    category: String,
    label: String,
    description: String,
    icon: ImageVector,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val borderColor = if (isSelected) color else Color.White.copy(alpha = 0.08f)
    val bgColor = if (isSelected) color.copy(alpha = 0.15f) else CardGlass
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp)
                    )
                }
                
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            
            Text(
                text = description,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                lineHeight = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
