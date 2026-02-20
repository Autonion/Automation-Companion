package com.autonion.automationcompanion.features.flow_automation.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autonion.automationcompanion.features.flow_automation.model.FlowNodeType
import com.autonion.automationcompanion.features.flow_automation.ui.editor.canvas.NodeColors

/**
 * Horizontal palette bar showing available node types to add.
 */
@Composable
fun NodePalette(
    onAddNode: (FlowNodeType) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nodeTypes = listOf(
        NodeTypeItem(FlowNodeType.GESTURE, "Gesture", "👆", NodeColors.GestureBlue),
        NodeTypeItem(FlowNodeType.VISUAL_TRIGGER, "Image Match", "🔍", NodeColors.VisualTriggerPurple),
        NodeTypeItem(FlowNodeType.SCREEN_ML, "Screen ML", "🧠", NodeColors.ScreenMLAmber),
        NodeTypeItem(FlowNodeType.DELAY, "Delay", "⏱", NodeColors.DelayGrey)
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1C1E)),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Add Node",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                TextButton(onClick = onDismiss) {
                    Text("✕", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(nodeTypes) { item ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(item.color.copy(alpha = 0.15f))
                            .clickable { onAddNode(item.nodeType) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(item.emoji, fontSize = 24.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.label,
                            color = item.color,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

private data class NodeTypeItem(
    val nodeType: FlowNodeType,
    val label: String,
    val emoji: String,
    val color: Color
)
