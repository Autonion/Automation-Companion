package com.autonion.automationcompanion.features.flow_automation.ui.editor.panels

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.*
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.NodeColors

/**
 * Bottom sheet panel for configuring a selected node's properties.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeConfigPanel(
    node: FlowNode,
    onUpdateNode: (FlowNode) -> Unit,
    onDeleteNode: () -> Unit,
    onLaunchOverlay: (FlowNode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (_, accentColor) = nodeColors(node)
    val focusManager = LocalFocusManager.current

    val configuration = LocalConfiguration.current
    val maxHeight = configuration.screenHeightDp.dp * 0.55f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1E))
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${nodeTypeEmoji(node)} ${node.label}",
                    color = accentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Color(0xFF64FFDA))
                }
            }

            // Warning banner if node attributes are unconfigured
            val warning = node.configurationWarning()
            if (warning != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF3E2723),
                    border = BorderStroke(1.dp, Color(0xFFFF9800)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("⚠", fontSize = 18.sp, color = Color(0xFFFF9800))
                        Column {
                            Text(
                                "Setup Required",
                                color = Color(0xFFFF9800),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                warning,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Label field (common to all nodes)
            var label by remember(node.id) { mutableStateOf(node.label) }
            OutlinedTextField(
                value = label,
                onValueChange = { newLabel ->
                    label = newLabel
                    onUpdateNode(updateNodeLabel(node, newLabel))
                },
                label = { Text("Node Label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = flowTextFieldColors()
            )

            Spacer(Modifier.height(12.dp))

            // Type-specific fields
            when (node) {
                is StartNode -> StartNodeConfig(node, onUpdateNode)
                is GestureNode -> GestureNodeConfig(node, onUpdateNode, onLaunchOverlay)
                is VisualTriggerNode -> VisualTriggerNodeConfig(node, onUpdateNode, onLaunchOverlay)
                is ScreenMLNode -> ScreenMLNodeConfig(node, onUpdateNode, onLaunchOverlay)
                is DelayNode -> DelayNodeConfig(node, onUpdateNode)
                is LaunchAppNode -> LaunchAppNodeConfig(node, onUpdateNode)
                is RepeatNode -> RepeatNodeConfig(node, onUpdateNode)
                is ClipboardNode -> ClipboardNodeConfig(node, onUpdateNode)
                is InputNode -> InputNodeConfig(node, onUpdateNode)
            }

            Spacer(Modifier.height(20.dp))

            // Delete button
            if (node !is StartNode) {
                Button(
                    onClick = onDeleteNode,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5A1A1A)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete Node", color = Color(0xFFEF5350))
                }
            }
        }
    }
}

// ─── App Picker Composable ───────────────────────────────────────────────────

/**
 * Data class for an installed app's info used in the picker.
 */
private data class AppItem(
    val appName: String,
    val packageName: String
)

/**
 * Searchable app picker dropdown using ExposedDropdownMenuBox.
 * Queries PackageManager for installed launchable apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerDropdown(
    selectedPackage: String,
    onAppSelected: (String?) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val context = LocalContext.current
    val installedApps = remember {
        val pm = context.packageManager
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        }
        pm.queryIntentActivities(mainIntent, 0)
            .mapNotNull { resolveInfo ->
                val pkgName = resolveInfo.activityInfo.packageName
                val appName = resolveInfo.loadLabel(pm).toString()
                // Exclude self
                if (pkgName == context.packageName) null
                else AppItem(appName, pkgName)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember(selectedPackage) {
        mutableStateOf(
            if (selectedPackage.isNotBlank()) {
                installedApps.find { it.packageName == selectedPackage }
                    ?.let { "${it.appName} (${it.packageName})" }
                    ?: selectedPackage
            } else ""
        )
    }

    val filteredApps = remember(searchQuery, installedApps) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val darkColorScheme = darkColorScheme(
        surface = Color(0xFF1E2024),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF2A2D33),
        onSurfaceVariant = Color.White.copy(alpha = 0.7f)
    )

    MaterialTheme(colorScheme = darkColorScheme) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = modifier
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    expanded = true
                    if (it.isBlank()) onAppSelected(null)
                },
                label = { Text("Target App") },
                placeholder = { Text("Search apps…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = flowTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { 
                    expanded = false
                    // Also clear focus to close the keyboard
                    focusManager.clearFocus()
                }),
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = expanded && filteredApps.isNotEmpty(),
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 220.dp)
            ) {
                // Clear option
                if (selectedPackage.isNotBlank()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "✕  Clear selection",
                                color = Color(0xFFEF5350),
                                fontSize = 13.sp
                            )
                        },
                        onClick = {
                            searchQuery = ""
                            onAppSelected(null)
                            expanded = false
                        }
                    )
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                }

                filteredApps.take(30).forEach { app ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    app.appName,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Text(
                                    app.packageName,
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp
                                )
                            }
                        },
                        onClick = {
                            searchQuery = "${app.appName} (${app.packageName})"
                            onAppSelected(app.packageName)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

// ─── Node-specific configs ───────────────────────────────────────────────────

@Composable
private fun StartNodeConfig(node: StartNode, onUpdate: (FlowNode) -> Unit) {
    AppPickerDropdown(
        selectedPackage = node.appPackageName ?: "",
        onAppSelected = { pkg ->
            onUpdate(node.copy(appPackageName = pkg))
        },
        accentColor = NodeColors.StartGreen
    )

    Spacer(Modifier.height(4.dp))
    Text(
        "Optional — leave empty to start flow without launching an app",
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 11.sp
    )
}

@Composable
private fun LaunchAppNodeConfig(node: LaunchAppNode, onUpdate: (FlowNode) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    AppPickerDropdown(
        selectedPackage = node.appPackageName,
        onAppSelected = { pkg ->
            onUpdate(node.copy(appPackageName = pkg ?: ""))
        },
        accentColor = NodeColors.LaunchAppTeal
    )

    Spacer(Modifier.height(12.dp))

    var delay by remember(node.id) { mutableStateOf(node.launchDelayMs.toString()) }
    OutlinedTextField(
        value = delay,
        onValueChange = {
            delay = it
            val ms = it.toLongOrNull() ?: return@OutlinedTextField
            onUpdate(node.copy(launchDelayMs = ms))
        },
        label = { Text("Launch Delay (ms)") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = flowTextFieldColors()
    )
    Text(
        "Time to wait for the app to fully open",
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 11.sp
    )
}

@Composable
private fun ClipboardNodeConfig(node: ClipboardNode, onUpdate: (FlowNode) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    
    // Operation Type
    Text("Operation", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ClipboardOperation.entries.forEach { op ->
            FilterChip(
                selected = node.operation == op,
                onClick = {
                    val updatedNode = if (op == ClipboardOperation.WRITE) {
                        val source = when (val currentSource = node.inputSource) {
                            is InputSource.Static -> {
                                if (currentSource.text.isEmpty()) {
                                    InputSource.FromContext(node.contextKey.ifBlank { "clipboard_text" })
                                } else {
                                    currentSource
                                }
                            }
                            is InputSource.FromContext -> {
                                val key = node.contextKey.ifBlank {
                                    currentSource.key.ifBlank { "clipboard_text" }
                                }
                                InputSource.FromContext(key)
                            }
                            is InputSource.Clipboard -> {
                                InputSource.FromContext(node.contextKey.ifBlank { "clipboard_text" })
                            }
                        }
                        node.copy(operation = op, inputSource = source)
                    } else {
                        node.copy(operation = op)
                    }
                    onUpdate(updatedNode)
                },
                label = { Text(op.name, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NodeColors.ClipboardBrown.copy(alpha = 0.3f),
                    selectedLabelColor = NodeColors.ClipboardBrown
                )
            )
        }
    }
    
    Spacer(Modifier.height(12.dp))

    if (node.operation == ClipboardOperation.READ) {
        OutlinedTextField(
            value = node.contextKey,
            onValueChange = { onUpdate(node.copy(contextKey = it)) },
            label = { Text("Context Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            colors = flowTextFieldColors()
        )
        Text(
            "Key to store clipboard content in context",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp
        )
    } else {
        Text("Write Source", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val isStatic = node.inputSource is InputSource.Static
            val isFromContext = node.inputSource is InputSource.FromContext
            FilterChip(
                selected = isStatic,
                onClick = { onUpdate(node.copy(inputSource = InputSource.Static(""))) },
                label = { Text("Static Text", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NodeColors.ClipboardBrown.copy(alpha = 0.3f),
                    selectedLabelColor = NodeColors.ClipboardBrown
                )
            )
            FilterChip(
                selected = isFromContext,
                onClick = {
                    val key = node.contextKey.ifBlank { "clipboard_text" }
                    onUpdate(node.copy(inputSource = InputSource.FromContext(key), contextKey = key))
                },
                label = { Text("From Context", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NodeColors.ClipboardBrown.copy(alpha = 0.3f),
                    selectedLabelColor = NodeColors.ClipboardBrown
                )
            )
        }

        Spacer(Modifier.height(12.dp))

        when (val source = node.inputSource) {
            is InputSource.Static -> {
                OutlinedTextField(
                    value = source.text,
                    onValueChange = { onUpdate(node.copy(inputSource = InputSource.Static(it))) },
                    label = { Text("Text to Copy") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    colors = flowTextFieldColors()
                )
            }
            is InputSource.FromContext -> {
                val key = source.key.ifBlank { node.contextKey }
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        onUpdate(node.copy(inputSource = InputSource.FromContext(it), contextKey = it))
                    },
                    label = { Text("Context Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    colors = flowTextFieldColors()
                )
            }
            is InputSource.Clipboard -> {
                val key = node.contextKey.ifBlank { "clipboard_text" }
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        onUpdate(node.copy(inputSource = InputSource.FromContext(it), contextKey = it))
                    },
                    label = { Text("Context Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    colors = flowTextFieldColors()
                )
            }
        }
    }
}

@Composable
private fun InputNodeConfig(node: InputNode, onUpdate: (FlowNode) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    
    // Source Type
    Text("Input Source", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    val isStatic = node.inputSource is InputSource.Static
    val isFromContext = node.inputSource is InputSource.FromContext
    val isClipboard = node.inputSource is InputSource.Clipboard
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = isStatic,
            onClick = { onUpdate(node.copy(inputSource = InputSource.Static(""))) },
            label = { Text("Static Text", fontSize = 12.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = NodeColors.InputPink.copy(alpha = 0.3f),
                selectedLabelColor = NodeColors.InputPink
            )
        )
        FilterChip(
            selected = isFromContext,
            onClick = { onUpdate(node.copy(inputSource = InputSource.FromContext(""))) },
            label = { Text("From Context", fontSize = 12.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = NodeColors.InputPink.copy(alpha = 0.3f),
                selectedLabelColor = NodeColors.InputPink
            )
        )
    }

    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = isClipboard,
            onClick = { onUpdate(node.copy(inputSource = InputSource.Clipboard)) },
            label = { Text("Paste Clipboard", fontSize = 12.sp) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = NodeColors.InputPink.copy(alpha = 0.3f),
                selectedLabelColor = NodeColors.InputPink
            )
        )
    }
    
    Spacer(Modifier.height(12.dp))
    
    when (val source = node.inputSource) {
        is InputSource.Static -> {
            OutlinedTextField(
                value = source.text,
                onValueChange = {
                    onUpdate(node.copy(inputSource = InputSource.Static(it)))
                },
                label = { Text("Text to Input") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = flowTextFieldColors()
            )
        }
        is InputSource.FromContext -> {
            OutlinedTextField(
                value = source.key,
                onValueChange = {
                    onUpdate(node.copy(inputSource = InputSource.FromContext(it)))
                },
                label = { Text("Context Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = flowTextFieldColors()
            )
        }
        is InputSource.Clipboard -> {
            Text(
                "Pastes clipboard into the focused field",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 11.sp
            )
        }
    }
    
    Spacer(Modifier.height(12.dp))
    
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Checkbox(
            checked = node.submitAfterInput,
            onCheckedChange = { onUpdate(node.copy(submitAfterInput = it)) },
            colors = CheckboxDefaults.colors(checkedColor = NodeColors.InputPink)
        )
        Text("Submit after input (Press Enter/Done)", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun GestureNodeConfig(node: GestureNode, onUpdate: (FlowNode) -> Unit, onLaunchOverlay: (FlowNode) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    if (node.recordedActionsJson.isNotEmpty()) {
        Text("✓ Recorded actions available.", color = Color(0xFF64FFDA), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = { onLaunchOverlay(node) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = NodeColors.GestureBlue),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(if (node.recordedActionsJson.isEmpty()) "Record Gesture" else "Re-record Gesture", color = Color.White)
    }

    // ── Collapsible Advanced Settings ──
    var showAdvanced by remember { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))
    TextButton(
        onClick = { showAdvanced = !showAdvanced },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (showAdvanced) "▾ Advanced Settings" else "▸ Advanced Settings",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }

    AnimatedVisibility(
        visible = showAdvanced,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column {
            Text(
                "Fallback config — used only if no recorded gesture is available",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))

            // Gesture type selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GestureType.entries.forEach { type ->
                    FilterChip(
                        selected = node.gestureType == type,
                        onClick = { onUpdate(node.copy(gestureType = type)) },
                        label = { Text(type.name, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NodeColors.GestureBlue.copy(alpha = 0.3f),
                            selectedLabelColor = NodeColors.GestureBlue
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Coordinate source
            when (val source = node.coordinateSource) {
                is CoordinateSource.Static -> {
                    var x by remember(node.id) { mutableStateOf(source.x.toString()) }
                    var y by remember(node.id) { mutableStateOf(source.y.toString()) }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = x,
                            onValueChange = {
                                x = it
                                val xf = it.toFloatOrNull() ?: return@OutlinedTextField
                                onUpdate(node.copy(coordinateSource = CoordinateSource.Static(xf, source.y)))
                            },
                            label = { Text("X") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = flowTextFieldColors()
                        )
                        OutlinedTextField(
                            value = y,
                            onValueChange = {
                                y = it
                                val yf = it.toFloatOrNull() ?: return@OutlinedTextField
                                onUpdate(node.copy(coordinateSource = CoordinateSource.Static(source.x, yf)))
                            },
                            label = { Text("Y") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            colors = flowTextFieldColors()
                        )
                    }
                }
                is CoordinateSource.FromContext -> {
                    var key by remember(node.id) { mutableStateOf(source.key) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdate(node.copy(coordinateSource = CoordinateSource.FromContext(it)))
                        },
                        label = { Text("Context Key") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors()
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            var duration by remember(node.id) { mutableStateOf(node.durationMs.toString()) }
            OutlinedTextField(
                value = duration,
                onValueChange = {
                    duration = it
                    val ms = it.toLongOrNull() ?: return@OutlinedTextField
                    onUpdate(node.copy(durationMs = ms))
                },
                label = { Text("Duration (ms)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = flowTextFieldColors()
            )
        }
    }
}

@Composable
private fun VisualTriggerNodeConfig(node: VisualTriggerNode, onUpdate: (FlowNode) -> Unit, onLaunchOverlay: (FlowNode) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    if (node.visionPresetJson.isNotEmpty()) {
        Text("✓ Vision configuration available.", color = Color(0xFF64FFDA), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = { onLaunchOverlay(node) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = NodeColors.VisualTriggerPurple),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(if (node.visionPresetJson.isEmpty()) "Identify Target Regions" else "Re-configure Target Regions", color = Color.White)
    }

    Spacer(Modifier.height(16.dp))

    var threshold by remember(node.id) { mutableStateOf(node.threshold) }
    var outputKey by remember(node.id) { mutableStateOf(node.outputContextKey) }

    Text("Threshold: ${String.format("%.2f", threshold)}", color = Color.White, fontSize = 13.sp)
    Slider(
        value = threshold,
        onValueChange = {
            threshold = it
            onUpdate(node.copy(threshold = it))
        },
        valueRange = 0.5f..1.0f,
        colors = SliderDefaults.colors(
            thumbColor = NodeColors.VisualTriggerPurple,
            activeTrackColor = NodeColors.VisualTriggerPurple
        )
    )

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = outputKey,
        onValueChange = {
            outputKey = it
            onUpdate(node.copy(outputContextKey = it))
        },
        label = { Text("Output Context Key") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = flowTextFieldColors()
    )

    // ── Collapsible Advanced Settings ──
    if (node.visionPresetJson.isNotEmpty()) {
        var showAdvanced by remember { mutableStateOf(false) }

        Spacer(Modifier.height(12.dp))
        TextButton(
            onClick = { showAdvanced = !showAdvanced },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (showAdvanced) "▾ Advanced Settings" else "▸ Advanced Settings",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }

        AnimatedVisibility(
            visible = showAdvanced,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                val preset = remember(node.visionPresetJson) {
                    try {
                        kotlinx.serialization.json.Json.decodeFromString<com.autonion.automationcompanion.features.visual_trigger.models.VisionPreset>(node.visionPresetJson)
                    } catch (e: Exception) { null }
                }

                if (preset != null) {
                    Text("Execution Mode", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        com.autonion.automationcompanion.features.visual_trigger.models.ExecutionMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preset.executionMode == mode,
                                onClick = {
                                    val updatedPreset = preset.copy(executionMode = mode)
                                    val newJson = kotlinx.serialization.json.Json.encodeToString(updatedPreset)
                                    onUpdate(node.copy(visionPresetJson = newJson))
                                },
                                label = { Text(mode.name.replace("_", " "), fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = NodeColors.VisualTriggerPurple.copy(alpha = 0.3f),
                                    selectedLabelColor = NodeColors.VisualTriggerPurple
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenMLNodeConfig(node: ScreenMLNode, onUpdate: (FlowNode) -> Unit, onLaunchOverlay: (FlowNode) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // ── Mode selector ──
    var selectedMode by remember(node.id) { mutableStateOf(node.mode) }

    Text("Detection Mode", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            ScreenMLMode.OBJECT_DETECTION,
            ScreenMLMode.UI_ATTRIBUTE,
            ScreenMLMode.OCR
        ).forEach { mode ->
            FilterChip(
                selected = selectedMode == mode,
                onClick = {
                    selectedMode = mode
                    onUpdate(node.copy(mode = mode))
                },
                label = {
                    Text(
                        when (mode) {
                            ScreenMLMode.OBJECT_DETECTION -> "🔲 Elements"
                            ScreenMLMode.UI_ATTRIBUTE -> "🧩 UI Attribute"
                            ScreenMLMode.OCR -> "📝 OCR"
                        },
                        fontSize = 11.5.sp
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NodeColors.ScreenMLAmber,
                    selectedLabelColor = Color.Black,
                    containerColor = Color(0xFF2A2D33),
                    labelColor = Color.White.copy(alpha = 0.7f)
                )
            )
        }
    }

    if (selectedMode == ScreenMLMode.UI_ATTRIBUTE) {
        Spacer(Modifier.height(2.dp))
        Text("✓ Screen recording only for capture • Flow execution runs directly via Accessibility",
            color = Color(0xFF64FFDA), fontSize = 11.sp)
    }

    Spacer(Modifier.height(10.dp))

    // ── Status indicator ──
    if (node.automationStepsJson.isNotEmpty()) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("✓ Screen Understanding actions configured", color = Color(0xFF64FFDA), fontSize = 12.sp)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = NodeColors.ScreenMLAmber.copy(alpha = 0.2f)
            ) {
                Text(
                    when (node.mode) {
                        ScreenMLMode.OCR -> "OCR"
                        ScreenMLMode.OBJECT_DETECTION -> "ELEMENTS"
                        ScreenMLMode.UI_ATTRIBUTE -> "UI ATTR"
                    },
                    color = NodeColors.ScreenMLAmber,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    // ── Capture / configure button (always available) ──
    Button(
        onClick = { onLaunchOverlay(node) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = NodeColors.ScreenMLAmber),
        shape = RoundedCornerShape(12.dp)
    ) {
        val btnText = if (node.mode == ScreenMLMode.UI_ATTRIBUTE) {
            if (node.automationStepsJson.isEmpty()) "Capture UI Attributes" else "Re-capture UI Attributes"
        } else {
            if (node.automationStepsJson.isEmpty()) "Capture & Detect Screen" else "Re-capture Screen"
        }
        Text(btnText, color = Color.Black)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        if (node.mode == ScreenMLMode.UI_ATTRIBUTE)
            "Captures accessibility elements directly without MediaProjection"
        else
            "Use the Elements or Text tab in the editor to choose detection mode",
        color = Color.White.copy(alpha = 0.35f),
        fontSize = 11.sp
    )

    Spacer(Modifier.height(12.dp))

    var outputKey by remember(node.id) { mutableStateOf(node.outputContextKey) }
    OutlinedTextField(
        value = outputKey,
        onValueChange = {
            outputKey = it
            onUpdate(node.copy(outputContextKey = it))
        },
        label = { Text("Output Context Key") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = flowTextFieldColors()
    )

    // ── Collapsible Advanced Settings ──
    var showAdvanced by remember { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))
    TextButton(
        onClick = { showAdvanced = !showAdvanced },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (showAdvanced) "▾ Advanced Settings" else "▸ Advanced Settings",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }

    AnimatedVisibility(
        visible = showAdvanced,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column {
            var targetLabel by remember(node.id) { mutableStateOf(node.targetLabel ?: "") }
            OutlinedTextField(
                value = targetLabel,
                onValueChange = {
                    targetLabel = it
                    onUpdate(node.copy(targetLabel = it.ifBlank { null }))
                },
                label = { Text("Target Label (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = flowTextFieldColors()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when (node.mode) {
                    ScreenMLMode.OCR -> "If set, the node will search for this text and report found/not-found"
                    ScreenMLMode.OBJECT_DETECTION -> "If set (without steps), the node will look for this element type"
                    ScreenMLMode.UI_ATTRIBUTE -> "If set (without steps), the node will look for this element label or text via accessibility"
                },
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun DelayNodeConfig(node: DelayNode, onUpdate: (FlowNode) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var delay by remember(node.id) { mutableStateOf(node.delayMs.toString()) }

    OutlinedTextField(
        value = delay,
        onValueChange = {
            delay = it
            val ms = it.toLongOrNull() ?: return@OutlinedTextField
            onUpdate(node.copy(delayMs = ms))
        },
        label = { Text("Delay (ms)") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = flowTextFieldColors()
    )

    Spacer(Modifier.height(8.dp))

    Text(
        "≈ ${(node.delayMs / 1000f)}s",
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp
    )
}

@Composable
private fun RepeatNodeConfig(node: RepeatNode, onUpdate: (FlowNode) -> Unit) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var isInfinite by remember(node.id) { mutableStateOf(node.repeatCount == 0) }
    var countText by remember(node.id) { mutableStateOf(if (node.repeatCount == 0) "" else node.repeatCount.toString()) }
    var delayText by remember(node.id) { mutableStateOf(node.delayBetweenMs.toString()) }

    // Infinite toggle
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text("Repeat Forever", color = Color.White, fontSize = 14.sp)
        Switch(
            checked = isInfinite,
            onCheckedChange = { checked ->
                isInfinite = checked
                if (checked) {
                    countText = ""
                    onUpdate(node.copy(repeatCount = 0))
                } else {
                    countText = "1"
                    onUpdate(node.copy(repeatCount = 1))
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = NodeColors.RepeatOrange,
                checkedTrackColor = NodeColors.RepeatOrange.copy(alpha = 0.3f)
            )
        )
    }

    Spacer(Modifier.height(4.dp))
    Text(
        if (isInfinite) "Will run until manually stopped"
        else "Will run the downstream nodes this many times",
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 11.sp
    )

    // Count field (disabled when infinite)
    AnimatedVisibility(
        visible = !isInfinite,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Column {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = countText,
                onValueChange = {
                    countText = it
                    val count = it.toIntOrNull() ?: return@OutlinedTextField
                    if (count > 0) onUpdate(node.copy(repeatCount = count))
                },
                label = { Text("Repeat Count") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                colors = flowTextFieldColors()
            )
        }
    }

    Spacer(Modifier.height(12.dp))

    // Delay between iterations
    OutlinedTextField(
        value = delayText,
        onValueChange = {
            delayText = it
            val ms = it.toLongOrNull() ?: return@OutlinedTextField
            onUpdate(node.copy(delayBetweenMs = ms))
        },
        label = { Text("Delay Between Iterations (ms)") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        colors = flowTextFieldColors()
    )

    if (node.delayBetweenMs > 0) {
        Spacer(Modifier.height(4.dp))
        Text(
            "≈ ${(node.delayBetweenMs / 1000f)}s between each iteration",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun flowTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White.copy(alpha = 0.8f),
    focusedBorderColor = Color(0xFF64FFDA),
    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
    focusedLabelColor = Color(0xFF64FFDA),
    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
    cursorColor = Color(0xFF64FFDA)
)

private fun nodeColors(node: FlowNode): Pair<Color, Color> {
    return when (node) {
        is StartNode -> NodeColors.StartGreenBg to NodeColors.StartGreen
        is GestureNode -> NodeColors.GestureBlueBg to NodeColors.GestureBlue
        is VisualTriggerNode -> NodeColors.VisualTriggerPurpleBg to NodeColors.VisualTriggerPurple
        is ScreenMLNode -> NodeColors.ScreenMLAmberBg to NodeColors.ScreenMLAmber
        is DelayNode -> NodeColors.DelayGreyBg to NodeColors.DelayGrey
        is LaunchAppNode -> NodeColors.LaunchAppTealBg to NodeColors.LaunchAppTeal
        is RepeatNode -> NodeColors.RepeatOrangeBg to NodeColors.RepeatOrange
        is ClipboardNode -> NodeColors.ClipboardBrownBg to NodeColors.ClipboardBrown
        is InputNode -> NodeColors.InputPinkBg to NodeColors.InputPink
    }
}

private fun nodeTypeEmoji(node: FlowNode): String = when (node) {
    is StartNode -> "▶"
    is GestureNode -> "👆"
    is VisualTriggerNode -> "🔍"
    is ScreenMLNode -> "🧠"
    is DelayNode -> "⏱"
    is LaunchAppNode -> "🚀"
    is RepeatNode -> "🔄"
    is ClipboardNode -> "📋"
    is InputNode -> "⌨️"
}

private fun updateNodeLabel(node: FlowNode, label: String): FlowNode = when (node) {
    is StartNode -> node.copy(label = label)
    is GestureNode -> node.copy(label = label)
    is VisualTriggerNode -> node.copy(label = label)
    is ScreenMLNode -> node.copy(label = label)
    is DelayNode -> node.copy(label = label)
    is LaunchAppNode -> node.copy(label = label)
    is RepeatNode -> node.copy(label = label)
    is ClipboardNode -> node.copy(label = label)
    is InputNode -> node.copy(label = label)
}
