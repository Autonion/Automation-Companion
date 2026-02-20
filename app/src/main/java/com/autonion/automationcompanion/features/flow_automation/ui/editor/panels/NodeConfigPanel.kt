package com.autonion.automationcompanion.features.flow_automation.ui.editor.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

    Card(
        modifier = modifier.fillMaxWidth(),
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

@Composable
private fun StartNodeConfig(node: StartNode, onUpdate: (FlowNode) -> Unit) {
    var pkg by remember(node.id) { mutableStateOf(node.appPackageName ?: "") }

    OutlinedTextField(
        value = pkg,
        onValueChange = {
            pkg = it
            onUpdate(node.copy(appPackageName = it.ifBlank { null }))
        },
        label = { Text("App Package Name") },
        placeholder = { Text("com.example.app") },
        modifier = Modifier.fillMaxWidth(),
        colors = flowTextFieldColors()
    )
}

@Composable
private fun GestureNodeConfig(node: GestureNode, onUpdate: (FlowNode) -> Unit, onLaunchOverlay: (FlowNode) -> Unit) {
    if (node.recordedActionsJson.isNotEmpty()) {
        Text("Recorded actions available.", color = Color(0xFF64FFDA), fontSize = 12.sp)
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

    Spacer(Modifier.height(16.dp))

    // Gesture type selector (Advanced)
    Text("Advanced: Fallback Config", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = flowTextFieldColors()
    )
}

@Composable
private fun VisualTriggerNodeConfig(node: VisualTriggerNode, onUpdate: (FlowNode) -> Unit, onLaunchOverlay: (FlowNode) -> Unit) {
    if (node.visionPresetJson.isNotEmpty()) {
        Text("Vision configuration available.", color = Color(0xFF64FFDA), fontSize = 12.sp)
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
        colors = flowTextFieldColors()
    )
}

@Composable
private fun ScreenMLNodeConfig(node: ScreenMLNode, onUpdate: (FlowNode) -> Unit, onLaunchOverlay: (FlowNode) -> Unit) {
    if (node.automationStepsJson.isNotEmpty()) {
        Text("Screen ML actions available.", color = Color(0xFF64FFDA), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
    }

    Button(
        onClick = { onLaunchOverlay(node) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = NodeColors.ScreenMLAmber),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(if (node.automationStepsJson.isEmpty()) "Capture & Detect Screen" else "Re-capture Screen", color = Color.Black)
    }

    Spacer(Modifier.height(16.dp))

    Text("Fallback Mode", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenMLMode.entries.forEach { mode ->
            FilterChip(
                selected = node.mode == mode,
                onClick = { onUpdate(node.copy(mode = mode)) },
                label = { Text(mode.name, fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NodeColors.ScreenMLAmber.copy(alpha = 0.3f),
                    selectedLabelColor = NodeColors.ScreenMLAmber
                )
            )
        }
    }

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
        colors = flowTextFieldColors()
    )
}

@Composable
private fun DelayNodeConfig(node: DelayNode, onUpdate: (FlowNode) -> Unit) {
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = flowTextFieldColors()
    )

    Spacer(Modifier.height(8.dp))

    Text(
        "≈ ${(node.delayMs / 1000f)}s",
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 12.sp
    )
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
    }
}

private fun nodeTypeEmoji(node: FlowNode): String = when (node) {
    is StartNode -> "▶"
    is GestureNode -> "👆"
    is VisualTriggerNode -> "🔍"
    is ScreenMLNode -> "🧠"
    is DelayNode -> "⏱"
}

private fun updateNodeLabel(node: FlowNode, label: String): FlowNode = when (node) {
    is StartNode -> node.copy(label = label)
    is GestureNode -> node.copy(label = label)
    is VisualTriggerNode -> node.copy(label = label)
    is ScreenMLNode -> node.copy(label = label)
    is DelayNode -> node.copy(label = label)
}
