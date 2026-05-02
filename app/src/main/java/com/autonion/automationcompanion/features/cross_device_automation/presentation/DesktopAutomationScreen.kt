package com.autonion.automationcompanion.features.cross_device_automation.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Work
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.autonion.automationcompanion.automation.actions.ui.AppPickerActivity
import com.autonion.automationcompanion.automation.actions.models.ConfiguredAction
import com.autonion.automationcompanion.automation.actions.ui.ActionPicker
import com.autonion.automationcompanion.features.cross_device_automation.CrossDeviceAutomationManager
import com.autonion.automationcompanion.features.cross_device_automation.domain.AutomationRule
import android.content.Intent

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
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emptyPulse"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.AutoFixHigh,
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .scale(pulseScale),
            tint = AccentPurple.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Create your first rule",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "e.g. \"Meeting → Enable DND\"",
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(CardGlass, CardGlass.copy(alpha = 0.4f))
                )
            )
            .background(CardBorder, RoundedCornerShape(16.dp))
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
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
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
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    categoryInfo.description,
                    color = categoryInfo.color.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${rule.actions.size} action${if (rule.actions.size != 1) "s" else ""}",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFFF6B6B).copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
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
//  CREATE RULE DIALOG (Kept mostly unchanged for function)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .height(600.dp),
        title = { Text(if (step == 1) "When..." else "Then...") },
        text = {
            Column(modifier = Modifier.fillMaxSize()) {
                if (step == 1) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Rule Name (e.g. Work Mode)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Trigger Condition", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))

                    val categories = listOf("Meeting", "Social", "Work", "Custom URL")
                    var expanded by remember { mutableStateOf(false) }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedCategory)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat) },
                                    onClick = {
                                        selectedCategory = cat
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (selectedCategory == "Custom URL") {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customUrl,
                            onValueChange = { customUrl = it },
                            label = { Text("URL Contains (e.g. youtube.com)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.lazy.LazyColumn {
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (step == 1) {
                        if (name.isNotBlank()) step = 2
                    } else {
                         onCreate(name, selectedCategory, customUrl, actions)
                    }
                },
                enabled = (step == 1 && name.isNotBlank()) || (step == 2 && actions.isNotEmpty())
            ) {
                Text(if (step == 1) "Next" else "Create Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (step == 2) step = 1 else onDismiss()
            }) {
                Text(if (step == 2) "Back" else "Cancel")
            }
        }
    )
}
