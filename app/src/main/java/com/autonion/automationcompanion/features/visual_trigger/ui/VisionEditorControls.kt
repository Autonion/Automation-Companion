package com.autonion.automationcompanion.features.visual_trigger.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.visual_trigger.models.*
import kotlin.math.roundToInt

private val EditorAccent = Color(0xFF47E78B)
private val EditorSurface = Color(0xFF22252B)

internal data class EditorToolAction(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val tint: Color = Color.White,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorTool(action: EditorToolAction) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(action.label) } },
        state = rememberTooltipState()
    ) {
        IconButton(onClick = action.onClick, enabled = action.enabled, modifier = Modifier.size(48.dp)) {
            Icon(action.icon, action.label, tint = if (action.enabled) action.tint else Color.Gray,
                modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun EditorChoice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (selected) EditorAccent.copy(alpha = 0.12f) else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = EditorAccent, unselectedColor = Color.LightGray))
        Text(label, modifier = Modifier.padding(12.dp), color = if (enabled) Color.White else Color.Gray)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorSettingsSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EditorSurface, contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                EditorTool(EditorToolAction("Close settings", Icons.Default.Close, onClick = onDismiss))
            }
            content()
        }
    }
}

@Composable
internal fun VisionRunSettings(
    mode: ExecutionMode, dispatch: TapDispatchMode, hasTap: Boolean, hasRotating: Boolean,
    onMode: (ExecutionMode) -> Unit, onDispatch: (TapDispatchMode) -> Unit, onDismiss: () -> Unit
) {
    EditorSettingsSheet("Run behavior", onDismiss) {
        EditorChoice("React to matches", mode == ExecutionMode.DETECT_ONLY) { onMode(ExecutionMode.DETECT_ONLY) }
        EditorChoice("Follow a sequence", mode != ExecutionMode.DETECT_ONLY, enabled = !hasRotating) {
            if (mode == ExecutionMode.DETECT_ONLY) onMode(ExecutionMode.MANDATORY_SEQUENTIAL)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (mode != ExecutionMode.DETECT_ONLY) {
            Text("Missing target", style = MaterialTheme.typography.titleSmall)
            EditorChoice("Wait", mode == ExecutionMode.MANDATORY_SEQUENTIAL) { onMode(ExecutionMode.MANDATORY_SEQUENTIAL) }
            EditorChoice("Skip", mode == ExecutionMode.OPTIONAL_SEQUENTIAL) { onMode(ExecutionMode.OPTIONAL_SEQUENTIAL) }
        } else if (hasTap) {
            Text("Tap delivery", style = MaterialTheme.typography.titleSmall)
            EditorChoice("One at a time", dispatch == TapDispatchMode.SEQUENTIAL) { onDispatch(TapDispatchMode.SEQUENTIAL) }
            EditorChoice("Concurrent", dispatch == TapDispatchMode.CONCURRENT) { onDispatch(TapDispatchMode.CONCURRENT) }
        }
    }
}

@Composable
internal fun VisionTargetSettings(
    region: VisionEditorViewModel.TempRegion, number: Int, mode: ExecutionMode,
    entireScreen: Boolean, onEntireScreen: () -> Unit, onCustomArea: () -> Unit,
    model: VisionEditorViewModel, onDelete: () -> Unit, onDismiss: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val actions = if (region.matchMode == VisionMatchMode.MOVING) {
        listOf(VisionAction.Click)
    } else {
        listOf(
            VisionAction.Click,
            VisionAction.LongClick,
            VisionAction.Scroll(ScrollDirection.UP),
            VisionAction.Scroll(ScrollDirection.DOWN)
        )
    }
    EditorSettingsSheet("Target #$number", onDismiss) {
        Text("Action", style = MaterialTheme.typography.titleSmall)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val canChangeAction = actions.size > 1
            Surface(
                onClick = { showActions = true },
                enabled = canChangeAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = if (canChangeAction) "Choose action" else "Action fixed for rotating target"
                    },
                shape = RoundedCornerShape(6.dp),
                color = Color.White.copy(alpha = 0.06f),
                border = BorderStroke(
                    1.dp,
                    if (showActions) EditorAccent else Color.White.copy(alpha = 0.18f)
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).background(EditorAccent.copy(alpha = 0.13f), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(region.action.editorIcon, null, tint = EditorAccent, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(region.action.editorLabel, fontWeight = FontWeight.SemiBold)
                        Text(
                            region.action.editorDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                    Text(
                        if (canChangeAction) "Change" else "Fixed",
                        color = if (canChangeAction) EditorAccent else Color.Gray,
                        style = MaterialTheme.typography.labelLarge
                    )
                    if (canChangeAction) {
                        Icon(Icons.Default.ExpandMore, null, tint = EditorAccent, modifier = Modifier.padding(start = 2.dp))
                    }
                }
            }
            DropdownMenu(
                expanded = showActions,
                onDismissRequest = { showActions = false },
                modifier = Modifier.width(maxWidth)
            ) {
                actions.forEach { action ->
                    val selected = region.action == action
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(action.editorLabel, fontWeight = FontWeight.Medium)
                                Text(
                                    action.editorDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                action.editorIcon,
                                null,
                                tint = if (selected) EditorAccent else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingIcon = {
                            if (selected) Icon(Icons.Default.Check, "Selected", tint = EditorAccent)
                        },
                        modifier = Modifier.heightIn(min = 60.dp),
                        onClick = {
                            model.updateRegionAction(region.id, action)
                            showActions = false
                        }
                    )
                }
            }
        }
        Text("Search area", style = MaterialTheme.typography.titleSmall)
        EditorChoice("Entire screen", entireScreen, onClick = onEntireScreen)
        EditorChoice("Custom area", region.searchRect != null && !entireScreen, onClick = onCustomArea)
        if (region.searchRect == null && !entireScreen) {
            // Preserve the saved sequential auto-area until the user explicitly changes it.
            EditorChoice("Near target (saved)", selected = true, onClick = {})
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Match mode", style = MaterialTheme.typography.titleSmall)
        EditorChoice("Fast match", region.matchMode == VisionMatchMode.STATIC) {
            model.updateRegionMatchMode(region.id, VisionMatchMode.STATIC)
        }
        EditorChoice("Rotating", region.matchMode == VisionMatchMode.MOVING,
            enabled = mode == ExecutionMode.DETECT_ONLY && region.action is VisionAction.Click) {
            model.updateRegionMatchMode(region.id, VisionMatchMode.MOVING)
        }
        TextButton(onClick = { advanced = !advanced }) {
            Text("Advanced", color = EditorAccent)
            Icon(Icons.Default.ExpandMore, null, tint = EditorAccent)
        }
        if (advanced) {
            val threshold = if (region.matchMode == VisionMatchMode.MOVING) region.movingMatchThreshold else region.matchThreshold
            Text("Match threshold: ${String.format("%.2f", threshold)}")
            Slider(value = threshold, onValueChange = { model.updateRegionThreshold(region.id, (it * 20).roundToInt() / 20f) },
                valueRange = 0.5f..1f, steps = 9)
            if (region.matchMode == VisionMatchMode.MOVING) {
                Text("Prediction lead: ${region.tapLeadMs} ms")
                Slider(value = region.tapLeadMs.toFloat(), onValueChange = { model.updateRegionTapLead(region.id, (it / 5).roundToInt() * 5) },
                    valueRange = 0f..150f, steps = 29)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            EditorTool(EditorToolAction("Delete target", Icons.Default.Delete, tint = Color(0xFFFF6D7D), onClick = onDelete))
        }
    }
}

internal val VisionAction.editorLabel: String
    get() = when (this) {
        is VisionAction.Click -> "Tap"
        is VisionAction.LongClick -> "Long press"
        is VisionAction.Scroll -> "Scroll ${direction.name.lowercase()}"
    }

internal val VisionAction.editorDescription: String
    get() = when (this) {
        is VisionAction.Click -> "Tap the center of each match"
        is VisionAction.LongClick -> "Press and hold each match"
        is VisionAction.Scroll -> when (direction) {
            ScrollDirection.UP -> "Swipe upward from each match"
            ScrollDirection.DOWN -> "Swipe downward from each match"
            ScrollDirection.LEFT -> "Swipe left from each match"
            ScrollDirection.RIGHT -> "Swipe right from each match"
        }
    }

internal val VisionAction.editorIcon: ImageVector
    get() = when (this) {
        is VisionAction.Click -> Icons.Default.TouchApp
        is VisionAction.LongClick -> Icons.Default.PanTool
        is VisionAction.Scroll -> when (direction) {
            ScrollDirection.UP -> Icons.Default.ArrowUpward
            ScrollDirection.DOWN -> Icons.Default.ArrowDownward
            ScrollDirection.LEFT -> Icons.AutoMirrored.Filled.ArrowBack
            ScrollDirection.RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
        }
    }

@Composable
internal fun VisionEditorToolbar(
    modifier: Modifier, landscape: Boolean, primary: List<EditorToolAction>, target: List<EditorToolAction>,
    targetLabel: String, searchLabel: String, page: Int, pages: Int, onPage: (Int) -> Unit
) {
    @Composable fun TargetLabel() {
        Column(Modifier.padding(horizontal = 8.dp)) {
            Text(targetLabel, color = Color.White, fontSize = 13.sp)
            if (searchLabel.isNotEmpty()) Text(searchLabel, color = Color.LightGray, fontSize = 11.sp)
        }
    }
    @Composable fun Pages() {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            EditorTool(EditorToolAction("Previous page", androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                enabled = page > 0) { onPage(page - 1) })
            Text("${page + 1}/$pages", color = Color.White, fontSize = 12.sp)
            EditorTool(EditorToolAction("Next page", androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward,
                enabled = page + 1 < pages) { onPage(page + 1) })
        }
    }
    Column(modifier.background(EditorSurface), horizontalAlignment = Alignment.CenterHorizontally) {
        if (landscape) {
            Row { EditorTool(primary.first()); EditorTool(primary.last()) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                primary.drop(1).dropLast(1).chunked(2).forEach { actions -> Row { actions.forEach { EditorTool(it) } } }
                HorizontalDivider()
                TargetLabel()
                target.chunked(2).forEach { actions -> Row { actions.forEach { EditorTool(it) } } }
                if (pages > 1) {
                    // A narrow rail uses vertically stacked navigation to keep all touch targets accessible.
                    EditorTool(EditorToolAction("Previous page", Icons.AutoMirrored.Filled.ArrowBack, enabled = page > 0) { onPage(page - 1) })
                    Text("${page + 1}/$pages", color = Color.White)
                    EditorTool(EditorToolAction("Next page", Icons.AutoMirrored.Filled.ArrowForward, enabled = page + 1 < pages) { onPage(page + 1) })
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TargetLabel() }
                target.forEach { EditorTool(it) }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { primary.forEach { EditorTool(it) } }
            if (pages > 1) Pages()
        }
    }
}
