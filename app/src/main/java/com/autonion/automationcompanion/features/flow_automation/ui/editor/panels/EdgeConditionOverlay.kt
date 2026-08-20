package com.autonion.automationcompanion.features.flow_automation.ui.editor.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.EdgeCondition
import com.autonion.automationcompanion.features.flow_automation.model.FlowEdge
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.FlowEditorColors
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.flowEditorColors

private enum class ConditionType(val label: String) {
    NONE("No Condition"),
    WAIT("Wait (seconds)"),
    IF_TEXT("If Text Contains"),
    IF_NOT_TEXT("If Text Doesn't Contain"),
    IF_EQUALS("If Context Equals"),
    IF_NOT_EQUALS("If Context Doesn't Equal"),
    IF_IMAGE("If Image Found"),
    IF_NOT_IMAGE("If Image Not Found"),
    RETRY("Retry on Failure"),
    ELSE("Else (Fallback)"),
    STOP("Stop Execution")
}

/**
 * Overlay dialog for configuring edge conditions.
 */
@Composable
fun EdgeConditionOverlay(
    edge: FlowEdge,
    onUpdateEdge: (FlowEdge) -> Unit,
    onDeleteEdge: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val editorColors = flowEditorColors()
    val isDark = isSystemInDarkTheme()

    var selectedType by remember(edge.id) {
        mutableStateOf(
            when (edge.condition) {
                null -> ConditionType.NONE
                is EdgeCondition.Always -> ConditionType.NONE
                is EdgeCondition.WaitSeconds -> ConditionType.WAIT
                is EdgeCondition.IfTextContains -> ConditionType.IF_TEXT
                is EdgeCondition.IfNotTextContains -> ConditionType.IF_NOT_TEXT
                is EdgeCondition.IfContextEquals -> ConditionType.IF_EQUALS
                is EdgeCondition.IfNotContextEquals -> ConditionType.IF_NOT_EQUALS
                is EdgeCondition.IfImageFound -> ConditionType.IF_IMAGE
                is EdgeCondition.IfNotImageFound -> ConditionType.IF_NOT_IMAGE
                is EdgeCondition.Retry -> ConditionType.RETRY
                is EdgeCondition.Else -> ConditionType.ELSE
                is EdgeCondition.StopExecution -> ConditionType.STOP
            }
        )
    }

    val configuration = LocalConfiguration.current
    val maxHeight = configuration.screenHeightDp.dp * 0.55f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = editorColors.panelBg),
        border = BorderStroke(1.dp, editorColors.panelText.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            val focusManager = LocalFocusManager.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Edge Condition", color = editorColors.panelText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton(onClick = onDismiss) {
                    Text("Done", color = editorColors.accentTealText)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Condition type selector
            ConditionType.entries.forEach { type ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    RadioButton(
                        selected = selectedType == type,
                        onClick = {
                            selectedType = type
                            val newCondition = when (type) {
                                ConditionType.NONE -> null
                                ConditionType.WAIT -> EdgeCondition.WaitSeconds(2f)
                                ConditionType.IF_TEXT -> EdgeCondition.IfTextContains("ml_result", "")
                                ConditionType.IF_NOT_TEXT -> EdgeCondition.IfNotTextContains("ml_result", "")
                                ConditionType.IF_EQUALS -> EdgeCondition.IfContextEquals("", "")
                                ConditionType.IF_NOT_EQUALS -> EdgeCondition.IfNotContextEquals("", "")
                                ConditionType.IF_IMAGE -> EdgeCondition.IfImageFound("match_result")
                                ConditionType.IF_NOT_IMAGE -> EdgeCondition.IfNotImageFound("match_result")
                                ConditionType.RETRY -> EdgeCondition.Retry()
                                ConditionType.ELSE -> EdgeCondition.Else
                                ConditionType.STOP -> EdgeCondition.StopExecution
                            }
                            onUpdateEdge(edge.copy(condition = newCondition))
                        },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = editorColors.accentTeal,
                            unselectedColor = editorColors.panelDimText
                        )
                    )
                    Text(
                        type.label,
                        color = editorColors.panelText,
                        modifier = Modifier.padding(top = 12.dp),
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Type-specific fields
            when (val condition = edge.condition) {
                is EdgeCondition.WaitSeconds -> {
                    var seconds by remember(edge.id) { mutableStateOf(condition.seconds.toString()) }
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = {
                            seconds = it
                            val s = it.toFloatOrNull() ?: return@OutlinedTextField
                            onUpdateEdge(edge.copy(condition = EdgeCondition.WaitSeconds(s)))
                        },
                        label = { Text("Seconds") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors(editorColors)
                    )
                }
                is EdgeCondition.IfTextContains -> {
                    var key by remember(edge.id) { mutableStateOf(condition.contextKey) }
                    var substring by remember(edge.id) { mutableStateOf(condition.substring) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfTextContains(it, substring)))
                        },
                        label = { Text("Context Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = flowTextFieldColors(editorColors)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = substring,
                        onValueChange = {
                            substring = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfTextContains(key, it)))
                        },
                        label = { Text("Target Substring") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors(editorColors)
                    )
                }
                is EdgeCondition.IfNotTextContains -> {
                    var key by remember(edge.id) { mutableStateOf(condition.contextKey) }
                    var substring by remember(edge.id) { mutableStateOf(condition.substring) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfNotTextContains(it, substring)))
                        },
                        label = { Text("Context Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = flowTextFieldColors(editorColors)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = substring,
                        onValueChange = {
                            substring = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfNotTextContains(key, it)))
                        },
                        label = { Text("Target Substring") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors(editorColors)
                    )
                }
                is EdgeCondition.IfContextEquals -> {
                    var key by remember(edge.id) { mutableStateOf(condition.key) }
                    var expectedValue by remember(edge.id) { mutableStateOf(condition.value) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfContextEquals(it, expectedValue)))
                        },
                        label = { Text("Context Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = flowTextFieldColors(editorColors)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = expectedValue,
                        onValueChange = {
                            expectedValue = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfContextEquals(key, it)))
                        },
                        label = { Text("Expected Value") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors(editorColors)
                    )
                }
                is EdgeCondition.IfNotContextEquals -> {
                    var key by remember(edge.id) { mutableStateOf(condition.key) }
                    var value by remember(edge.id) { mutableStateOf(condition.value) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfNotContextEquals(it, value)))
                        },
                        label = { Text("Context Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        colors = flowTextFieldColors(editorColors)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfNotContextEquals(key, it)))
                        },
                        label = { Text("Rejected Value") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors(editorColors)
                    )
                }
                is EdgeCondition.IfImageFound -> {
                    var key by remember(edge.id) { mutableStateOf(condition.contextKey) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfImageFound(it)))
                        },
                        label = { Text("Image Result Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors(editorColors)
                    )
                }
                is EdgeCondition.IfNotImageFound -> {
                    var key by remember(edge.id) { mutableStateOf(condition.contextKey) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfNotImageFound(it)))
                        },
                        label = { Text("Image Result Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors(editorColors)
                    )
                }
                is EdgeCondition.Retry -> {
                    var maxAttempts by remember(edge.id) { mutableStateOf(condition.maxAttempts.toString()) }
                    var delayMs by remember(edge.id) { mutableStateOf(condition.delayMs.toString()) }
                    OutlinedTextField(
                        value = maxAttempts,
                        onValueChange = {
                            maxAttempts = it
                            val r = it.toIntOrNull() ?: return@OutlinedTextField
                            onUpdateEdge(edge.copy(condition = condition.copy(maxAttempts = r)))
                        },
                        label = { Text("Max Attempts") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        colors = flowTextFieldColors(editorColors)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = delayMs,
                        onValueChange = {
                            delayMs = it
                            val d = it.toLongOrNull() ?: return@OutlinedTextField
                            onUpdateEdge(edge.copy(condition = condition.copy(delayMs = d)))
                        },
                        label = { Text("Delay Between Retries (ms)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        colors = flowTextFieldColors(editorColors)
                    )
                }
                else -> { /* No config needed */ }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onDeleteEdge,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDark) Color(0xFF5A1A1A) else Color(0xFFFFEBEE)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Delete Edge", color = Color(0xFFEF5350), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun flowTextFieldColors(editorColors: FlowEditorColors = flowEditorColors()) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = editorColors.panelText,
    unfocusedTextColor = editorColors.panelText.copy(alpha = 0.85f),
    focusedBorderColor = editorColors.accentTeal,
    unfocusedBorderColor = editorColors.panelText.copy(alpha = 0.25f),
    focusedLabelColor = editorColors.accentTealText,
    unfocusedLabelColor = editorColors.panelDimText,
    cursorColor = editorColors.accentTeal
)
