package com.autonion.automationcompanion.features.flow_automation.ui.editor.panels

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.EdgeCondition
import com.autonion.automationcompanion.features.flow_automation.model.FlowEdge

private enum class ConditionType(val label: String) {
    NONE("No Condition"),
    WAIT("Wait (seconds)"),
    IF_TEXT("If Text Contains"),
    IF_EQUALS("If Context Equals"),
    IF_IMAGE("If Image Found"),
    RETRY("Retry on Failure")
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
    var selectedType by remember(edge.id) {
        mutableStateOf(
            when (edge.condition) {
                null -> ConditionType.NONE
                is EdgeCondition.Always -> ConditionType.NONE
                is EdgeCondition.WaitSeconds -> ConditionType.WAIT
                is EdgeCondition.IfTextContains -> ConditionType.IF_TEXT
                is EdgeCondition.IfContextEquals -> ConditionType.IF_EQUALS
                is EdgeCondition.IfImageFound -> ConditionType.IF_IMAGE
                is EdgeCondition.Retry -> ConditionType.RETRY
            }
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1E))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Edge Condition", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Color(0xFF64FFDA))
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
                                ConditionType.IF_EQUALS -> EdgeCondition.IfContextEquals("", "")
                                ConditionType.IF_IMAGE -> EdgeCondition.IfImageFound("match_result")
                                ConditionType.RETRY -> EdgeCondition.Retry()
                            }
                            onUpdateEdge(edge.copy(condition = newCondition))
                        },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = Color(0xFF64FFDA),
                            unselectedColor = Color.White.copy(alpha = 0.5f)
                        )
                    )
                    Text(
                        type.label,
                        color = Color.White,
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
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = flowTextFieldColors()
                    )
                }
                is EdgeCondition.IfTextContains -> {
                    var key by remember(edge.id) { mutableStateOf(condition.contextKey) }
                    var text by remember(edge.id) { mutableStateOf(condition.substring) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfTextContains(it, condition.substring)))
                        },
                        label = { Text("Context Key") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = flowTextFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfTextContains(condition.contextKey, it)))
                        },
                        label = { Text("Contains Text") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = flowTextFieldColors()
                    )
                }
                is EdgeCondition.IfContextEquals -> {
                    var key by remember(edge.id) { mutableStateOf(condition.key) }
                    var value by remember(edge.id) { mutableStateOf(condition.value) }
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfContextEquals(it, condition.value)))
                        },
                        label = { Text("Context Key") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = flowTextFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            onUpdateEdge(edge.copy(condition = EdgeCondition.IfContextEquals(condition.key, it)))
                        },
                        label = { Text("Expected Value") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = flowTextFieldColors()
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
                        label = { Text("Context Key (from Image Match node)") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = flowTextFieldColors()
                    )
                }
                is EdgeCondition.Retry -> {
                    var attempts by remember(edge.id) { mutableStateOf(condition.maxAttempts.toString()) }
                    var delay by remember(edge.id) { mutableStateOf(condition.delayMs.toString()) }
                    OutlinedTextField(
                        value = attempts,
                        onValueChange = {
                            attempts = it
                            val a = it.toIntOrNull() ?: return@OutlinedTextField
                            onUpdateEdge(edge.copy(condition = EdgeCondition.Retry(a, condition.delayMs)))
                        },
                        label = { Text("Max Attempts") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = flowTextFieldColors()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = delay,
                        onValueChange = {
                            delay = it
                            val d = it.toLongOrNull() ?: return@OutlinedTextField
                            onUpdateEdge(edge.copy(condition = EdgeCondition.Retry(condition.maxAttempts, d)))
                        },
                        label = { Text("Delay Between Retries (ms)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = flowTextFieldColors()
                    )
                }
                else -> { /* No config needed */ }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onDeleteEdge,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Delete Edge", color = Color(0xFFEF5350))
            }
        }
    }
}

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
